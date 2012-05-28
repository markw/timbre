(ns timbre.core
  "Simple, flexible, all-Clojure logging. No XML!"
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [clj-stacktrace.repl :as stacktrace]
            [postal.core :as postal]))

;;;; Appender-fn helpers

(defn instant-str
  "2012-May-26 15:26:06:081 +0700"
  [instant]
  (format "%1$tY-%1$tb-%1$td %1$tH:%1$tM:%1$tS:%1$tL %1tz" instant))

(defn prefixed-message
  "2012-May-26 15:26:06:081 +0700 LEVEL [ns] - message"
  [level instant ns message]
  (str (instant-str instant) " " (-> level name str/upper-case)
       " [" ns "] - " message))

;;;; Default configuration and appenders

(def config
  "This map atom controls everything about the way Timbre operates. In
  particular note the flexibility to add arbitrary appenders.

  An appender is a map with keys:
    :doc, :min-level, :enabled?, :async?, :max-message-per-msecs, :fn?

  An appender's fn takes a single map argument with keys:
    :ap-config, :level, :error?, :instant, :ns, :message, :more

  See source code for examples."
  (atom {:current-level :debug

         :appenders
         {:standard-out
          {:doc "Prints everything to *out*."
           :min-level :debug :enabled? false :async? false
           :max-message-per-msecs nil
           :fn (fn [{:keys [level instant ns message more]}]
                 (apply println (prefixed-message level instant ns message)
                        more))}

          :standard-out-or-err
          {:doc "Prints to *out* or *err* as appropriate. Enabled by default."
           :min-level :debug :enabled? true :async? false
           :max-message-per-msecs nil
           :fn (fn [{:keys [level error? instant ns message more]}]
                 (binding [*out* (if error? *err* *out*)]
                   (apply println (prefixed-message level instant ns message)
                          more)))}

          :postal
          {:doc (str "Sends an email using com.draines/postal.\n"
                     "Needs :postal config map in :shared-appender-config.")
           :min-level :error :enabled? false :async? true
           :max-message-per-msecs (* 60 60 2)
           :fn (fn [{:keys [ap-config level instant ns message more]}]
                 (when-let [postal-config (:postal ap-config)]
                   (postal/send-message
                    (assoc postal-config
                      :subject (prefixed-message level instant ns message)
                      :body (if (seq more) (str/join " " more)
                                "<no additional arguments>")))))}}

         ;; Example :postal map:
         ;; ^{:host "mail.isp.net" :user "jsmith" :pass "sekrat!!1"}
         ;; {:from "me@draines.com" :to "foo@example.com"}
         :shared-appender-config {:postal nil}}))

(defn set-config! [ks val] (swap! config assoc-in ks val))
(defn set-level!  [level]  (set-config! [:current-level] level))

;;;; Define and sort logging levels

(def ^:private ordered-levels [:trace :debug :info :warn :error :fatal])
(def ^:private scored-levels  (zipmap ordered-levels (range)))

(def compare-levels
  (memoize (fn [x y] (- (scored-levels x) (scored-levels y)))))

(defn sufficient-level?
  [level] (>= (compare-levels level (:current-level @config)) 0))

;;;; Appender-fn decoration: flood control, async, etc.

(defn- wrap-appender-fn
  "Wraps compile-time appender fn with additional capabilities controlled by
  compile-time config."
  [appender-id {apfn :fn :keys [async? max-message-per-msecs] :as appender}]
  (->
   ;; Wrap to add shared appender config to args
   (fn [apfn-args]
     (apfn (assoc apfn-args :ap-config (@config :shared-appender-config))))

   ;; Wrap for asynchronicity support
   ((fn [apfn]
      (if-not async?
        apfn
        (let [agent (agent nil :error-mode :continue)]
          (fn [apfn-args] (send-off agent (fn [_] (apfn apfn-args))))))))

   ;; Wrap for runtime flood-safety support
   ((fn [apfn]
      (if-not max-message-per-msecs
        apfn
        (let [ ;; {:msg last-appended-time-msecs ...}
              flood-timers (atom {})]

          (fn [{:keys [message] :as apfn-args}]
            (let [now    (System/currentTimeMillis)
                  allow? (fn [last-msecs]
                           (if last-msecs
                             (> (- now last-msecs) max-message-per-msecs)
                             true))]

              (when (allow? (@flood-timers message))
                (apfn apfn-args)
                (swap! flood-timers assoc message now))

              ;; Occassionally garbage-collect all expired timers. Note
              ;; that due to snapshotting, garbage-collection can cause
              ;; some appenders to re-append prematurely.
              (when (< (rand) 0.001)
                (let [timers-snapshot @flood-timers
                      expired-timers
                      (->> (keys timers-snapshot)
                           (filter #(allow? (timers-snapshot %))))]
                  (when (seq expired-timers)
                    (apply swap! flood-timers dissoc expired-timers))))))))))))

;;;; Appender-fn caching

(def juxt-cache
  "Per-level, combined relevant appender-fns:
  {:level (juxt wrapped-appender-fn wrapped-appender-fn ...) or nil
    ...}"
  (atom {}))

(defn- relevant-appenders
  [level]
  (->> (:appenders @config)
       (filter #(let [{:keys [enabled? min-level]} (val %)]
                  (and enabled? (>= (compare-levels level min-level) 0))))
       (into {})))

(comment (relevant-appenders :debug)
         (relevant-appenders :trace))

(defn- cache-appenders!
  "Prime 'juxt-cache' for fast runtime appender-fn dispatch."
  []
  (->> (zipmap
        ordered-levels
        (->> ordered-levels
             (map (fn [l] (let [rel-aps (relevant-appenders l)]
                           ;; Return nil if no relevant appenders
                           (when-let [ap-ids (keys rel-aps)]
                             (->> ap-ids
                                  (map (fn [n] (wrap-appender-fn
                                               n (rel-aps n))))
                                  (apply juxt))))))))
       (reset! juxt-cache)))

(cache-appenders!) ; Actually cache default appenders now

;; Automatically re-cache any time appenders change
(add-watch
 config "appender-watch"
 (fn [key ref old new]
   (when-not (= (:appenders old) (:appenders new))
     (cache-appenders!))))

;;;; Define logging macros

(defmacro log
  "Dispatches given arguments to relevant appender-fns iff logging level is
  sufficient."
  {:arglists '([message & more] [throwable message & more])}
  [level & args]
  `(let [level# ~level]
     (when (sufficient-level? level#)
       (when-let [juxt-fn# (@juxt-cache level#)] ; Any relevant appenders?
         (let [[x1# & xs#] (list ~@args)

               has-throwable?# (instance? Throwable x1#)
               appender-args#
               {:level     level#
                :error?    (>= (compare-levels level# :error) 0)
                :instant   (java.util.Date.)
                :ns        (str ~*ns*)
                :message   (if has-throwable?# (or (first xs#) x1#) x1#)
                :more      (if has-throwable?#
                             (conj (vec (rest xs#))
                                   (str "\n" (stacktrace/pst-str x1#)))
                             (vec xs#))}]

           (juxt-fn# appender-args#)
           nil)))))

(comment (log :fatal "arg1")
         (log :debug "arg1" "arg2")
         (log :debug (Exception.) "arg1" "arg2")
         (log :debug (Exception.))
         (log :trace "arg1"))

(defmacro spy
  "Evaluates expression and logs its form and result. Returns the result.
  Defaults to :debug logging level."
  ([expr] `(spy :debug ~expr))
  ([level expr]
     `(try
        (let [r# ~expr] (log ~level '~expr ~expr) r#)
        (catch Exception e#
          (log ~level '~expr (str "\n" (stacktrace/pst-str e#)))
          (throw e#)))))

(defmacro ^:private def-logger
  [level]
  (let [level-name (name level)]
    `(defmacro ~(symbol level-name)
       ~(str "Log given arguments at " (str/capitalize level-name) " level.")
       ~'{:arglists '([message & more] [throwable message & more])}
       [& args#]
       `(log ~~level ~@args#))))

(defmacro ^:private def-loggers
  [] `(do ~@(map (fn [level] `(def-logger ~level)) ordered-levels)))

(def-loggers) ; Actually define a logger for each logging level

;;;; Dev/tests

(comment
  (info "foo" "bar")
  (trace (Thread/sleep 5000))
  (time (dotimes [n 10000] (trace "This won't log"))) ; Minimum overhead +/- 17ms
  (time (dotimes [n 5] (info "foo" "bar")))
  (spy (* 6 5 4 3 2 1))
  (info (Exception. "noes!") "bar")
  (spy (/ 4 0)))