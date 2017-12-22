(ns mesomatic.examples.standard.executor
  (:require
    [clojure.core.async :as a :refer [chan <! go]]
    [clojure.string :as string]
    [clojusc.twig :refer [pprint]]
    [mesomatic.async.executor :as async-executor]
    [mesomatic.examples.standard.task :as task]
    [mesomatic.examples.util :as util]
    [mesomatic.executor :as executor :refer [executor-driver]]
    [mesomatic.types :as types]
    [taoensso.timbre :as log]))

;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
;;; Constants and Data
;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

(def lein "/usr/local/bin/lein")

(defn info-map
  ""
  []
  {:executor-id (util/get-uuid)
   :name "Example Executor (Clojure)"})

(defn cmd-info-map
  ""
  [master-info framework-id cwd]
  (into
    (info-map)
    {:framework-id {:value framework-id}
     :resources [{:name "cpus" :scalar 0.1 :type :value-scalar}
                 {:name "mem" :scalar 128 :type :value-scalar}]
     :command {:value (format "cd %s && %s mesomatic %s:%s executor"
                              cwd
                              lein
                              (:hostname master-info)
                              (:port master-info))
               :shell true}}))

(defn get-executor-id
  ""
  [payload]
  (get-in payload [:executor-info :executor-id :value]))

(defn get-task-id
  ""
  [payload]
  (get-in payload [:task :task-id :value]))

;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
;;; Utility functions
;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

(defn send-log
  ""
  [state level message]
  (let [str-level (string/upper-case (name level))
        msg (format "%s - %s" str-level message)
        bytes (.getBytes (str "Message from executor: " msg))]
    (if (= level :debug)
      (log/debugf "Sending message to framework: %s ..." msg))
    (executor/send-framework-message! (:driver state) bytes)))

(defn send-log-trace
  ""
  [state message]
  (send-log state :trace message))

(defn send-log-debug
  ""
  [state message]
  (send-log state :debug message))

(defn send-log-info
  ""
  [state message]
  (send-log state :info message))

(defn send-log-warn
  ""
  [state message]
  (send-log state :warn message))

(defn send-log-error
  ""
  [state message]
  (send-log state :error message))

(defn run-task
  ""
  [task-id state payload]
  (let [executor-id (get-executor-id payload)
        driver (:driver state)]
    (executor/send-status-update!
      driver
      (task/status-running executor-id task-id))
    (send-log-info state (str "Running task " task-id))

    ;; This is where one would perform the requested task:
    ;; ...
    (Thread/sleep (rand-int 500))
    ;; ...
    ;; Task complete; next step should be to let the system know. First,
    ;; though, if you have any updates you want to make to the state that will
    ;; be used by the 'task-success' state change, you can do that here (we
    ;; don't need to in this example).
    state))

(defn update-task-success
  ""
  [task-id state payload]
  (let [executor-id (get-executor-id payload)
        driver (:driver state)]
    (executor/send-status-update!
      driver
      (task/status-finished executor-id task-id))
    (send-log-info state (str "Finished task " task-id))))

(defn update-task-fail
  ""
  [task-id e state payload]
  (let [executor-id (get-executor-id payload)]
    (send-log-error state (format "Got exception for task %s: %s"
                                  task-id (pprint e)))
    (executor/send-status-update!
      (:driver state)
      (task/status-failed executor-id task-id))
    (send-log-info state (format "Task %s failed" task-id))))

(defn launch-task
  ""
  [task-id state payload]
  (try
    (as-> state new-state
          (run-task task-id new-state payload)
          (update-task-success task-id new-state payload))
    (catch Exception e
      (log/error "There was a problem running the task:" e)
      (update-task-fail task-id e state payload))))

;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
;;; Framework callbacks
;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
;;;
;;; Note that these are not callbacks in the node.js or even Twisted (Python)
;;; sense of the word; they are like Erlang OTP callbacks. For more
;;; information on the distinguishing characteristics, take a look at Joe
;;; Armstrong's blog post on Red/Green Callbacks:
;;;  * http://joearms.github.io/2013/04/02/Red-and-Green-Callbacks.html

(defmulti handle-msg (comp :type last vector))

(defmethod handle-msg :registered
  [state payload]
  (send-log-info state (str "Registered executor: " (get-executor-id payload)))
  state)

(defmethod handle-msg :reregistered
  [state payload]
  (send-log-info state
                 (str "Reregistered executor: " (get-executor-id payload)))
  state)

(defmethod handle-msg :disconnected
  [state payload]
  (send-log-info state (str "Executor has disconnected: " (pprint payload)))
  state)

(defmethod handle-msg :launch-task
  [state payload]
  (let [task-id (get-task-id payload)]
    (send-log-info state (format "Launching task %s ..." task-id))
    (log/debug "Task id:" task-id)
    (send-log-trace state (str "Task payload: " (pprint payload)))
    (-> (launch-task task-id state payload)
        (Thread.)
        (.start))
    state))

(defmethod handle-msg :kill-task
  [state payload]
  (send-log-info state (str "Killing task: " (pprint payload)))
  state)

(defmethod handle-msg :framework-message
  [state payload]
  (send-log-info state (str "Got framework message: " (pprint payload)))
  state)

(defmethod handle-msg :shutdown
  [state payload]
  (send-log-info state (str "Shutting down executor: " (pprint payload)))
  state)

(defmethod handle-msg :error
  [state payload]
  (send-log-error state (str "Error in executor: " (pprint payload)))
  state)

(defmethod handle-msg :default
  [state payload]
  (send-log-warn (str "Unhandled message: " (pprint payload)))
  state)

;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
;;; Executor entrypoint
;;; >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

(defn run
  ""
  [master]
  (log/infof "Running example executor from %s ..." (util/cwd))
  (let [ch (chan)
        exec (async-executor/executor ch)
        driver (executor-driver exec)]
    (log/debug "Starting example executor ...")
    (executor/start! driver)
    (log/debug "Reducing over example executor channel messages ...")
    (a/reduce handle-msg {:driver driver
                          :ch ch} ch)
    (executor/join! driver)))
