; Copyright 2013 Relevance, Inc.
; Copyright 2014 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.interceptor-test
  (:require [clojure.test :refer (deftest is)]
            [clojure.core.async :refer [<! >! go chan timeout <!! >!!]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.helpers :refer (definterceptor defaround defmiddleware)]
            [io.pedestal.interceptor.chain :as chain :refer (execute execute-only enqueue)]))

(defn trace [context direction name]
  (update-in context [::trace] (fnil conj []) [direction name]))

(defn tracer [name]
  (interceptor/interceptor {:name name
                            :enter #(trace % :enter name)
                            :leave #(trace % :leave name)}))

(defn thrower [name]
  (assoc (tracer name)
         :enter (fn [context] (throw (ex-info "Boom!" {:from name})))))

(defn leave-thrower [name]
  (assoc (tracer name)
         :leave (fn [context] (throw (ex-info "Boom!" {:from name})))))

(defn catcher [name]
  (assoc (tracer name)
         :error (fn [context error]
                  (update-in context [::trace] (fnil conj [])
                             [:error name :from (:from (ex-data error))]))))

(defn channeler [name]
  (assoc (tracer name)
         :enter (fn [context]
                  (let [a-chan (chan)
                        context* (-> (trace context :enter name)
                                     (update-in [::thread-ids] (fnil conj []) (.. Thread currentThread getId)))]
                    (go
                      (<! (timeout 100))
                      (>! a-chan context*))
                    a-chan))))

(defn deliverer [ch]
  (interceptor/interceptor {:name ::deliverer
                            :leave #(do (>!! ch %)
                                        ch)}))

(defn enter-deliverer [ch]
  (interceptor/interceptor {:name ::deliverer
                            :enter #(do (>!! ch %)
                                        ch)}))

(deftest t-simple-execution
  (let [expected {::trace [[:enter :a]
                           [:enter :b]
                           [:enter :c]
                           [:leave :c]
                           [:leave :b]
                           [:leave :a]]}
        actual-en (execute (enqueue {}
                                    [(tracer :a)
                                     (tracer :b)
                                     (tracer :c)]))
        actual-en* (execute (chain/enqueue* {}
                                            (tracer :a)
                                            (tracer :b)
                                            (tracer :c)))
        actual-ex (execute {} [(tracer :a)
                               (tracer :b)
                               (tracer :c)])]
    (is (= expected actual-en actual-en* actual-ex))))

(deftest t-simple-oneway-execution
  (let [expected-enter {::trace [[:enter :a]
                                 [:enter :b]
                                 [:enter :c]]}
        expected-leave {::trace [[:leave :c]
                                 [:leave :b]
                                 [:leave :a]]}
        actual-en (execute-only (enqueue {}
                                         [(tracer :a)
                                          (tracer :b)
                                          (tracer :c)])
                                :enter)
        actual-en* (execute-only (chain/enqueue* {}
                                                 (tracer :a)
                                                 (tracer :b)
                                                 (tracer :c))
                                 :enter)
        actual-ex (execute-only {} :enter [(tracer :a)
                                           (tracer :b)
                                           (tracer :c)])]
    (is (= expected-enter actual-en actual-en* actual-ex))
    (is (= expected-leave
           (execute-only (enqueue {}
                                  [(tracer :a)
                                   (tracer :b)
                                   (tracer :c)])
                         :leave)))))

(deftest t-error-propagates
  (is (thrown? Exception
               (execute (enqueue {}
                                 [(tracer :a)
                                  (tracer :b)
                                  (thrower :c)
                                  (tracer :d)]))))
  (is (thrown? Exception
               (execute-only (enqueue {}
                                      [(tracer :a)
                                       (tracer :b)
                                       (thrower :c)
                                       (tracer :d)])
                             :enter))))

(deftest t-error-caught
  (is (= {::trace [[:enter :a]
                   [:enter :b]
                   [:enter :c]
                   [:enter :d]
                   [:enter :e]
                   [:error :c :from :f]
                   [:leave :b]
                   [:leave :a]]}
         (execute (enqueue {}
                           [(tracer :a)
                            (tracer :b)
                            (catcher :c)
                            (tracer :d)
                            (tracer :e)
                            (thrower :f)
                            (tracer :g)]))))
  (is (= {::trace [[:enter :a]
                   [:enter :b]
                   [:enter :c]
                   [:enter :d]
                   [:enter :e]
                   [:error :c :from :f]]}
         (execute-only (enqueue {}
                                [(tracer :a)
                                 (tracer :b)
                                 (catcher :c)
                                 (tracer :d)
                                 (tracer :e)
                                 (thrower :f)
                                 (tracer :g)])
                       :enter)))
  (is (= {::trace [[:leave :h]
                   [:leave :g]
                   [:error :h :from :f]]}
         (execute-only (enqueue {}
                                [(tracer :a)
                                 (tracer :b)
                                 (catcher :c)
                                 (tracer :d)
                                 (tracer :e)
                                 (leave-thrower :f)
                                 (tracer :g)
                                 (catcher :h)])
                       :leave))))

(deftest t-two-channels
  (let [result-chan (chan)
        res (execute (enqueue {}
                              [(deliverer result-chan)
                               (tracer :a)
                               (channeler :b)
                               (channeler :c)
                               (tracer :d)]))
        result     (<!! result-chan)
        trace      (result ::trace)
        thread-ids (result ::thread-ids)]
    (is (= [[:enter :a]
            [:enter :b]
            [:enter :c]
            [:enter :d]
            [:leave :d]
            [:leave :c]
            [:leave :b]
            [:leave :a]]
           trace))
    (is (= 2
           (-> thread-ids distinct count)))))

(deftest t-two-channels-with-error
  (let [result-chan (chan)
        res (execute (enqueue {}
                              [(deliverer result-chan)
                               (tracer :a)
                               (catcher :b)
                               (channeler :c)
                               (tracer :d)
                               (thrower :e)
                               (tracer :f)]))
        result     (<!! result-chan)
        trace      (result ::trace)
        thread-ids (result ::thread-ids)]
    (is (= [[:enter :a]
            [:enter :b]
            [:enter :c]
            [:enter :d]
            ;; :e throws, gets caught by :b
            [:error :b :from :e]
            ;; Finish and unwind the stack
            [:leave :a]]
           trace))
    (is (= 1
           (-> thread-ids distinct count)))))

(deftest one-way-async-channel
  (let [result-chan (chan)
        res (execute-only (enqueue {}
                              [(tracer :a)
                               (channeler :b)
                               (channeler :c)
                               (tracer :d)
                               (enter-deliverer result-chan)])
                          :enter)
        result     (<!! result-chan)
        trace      (result ::trace)
        thread-ids (result ::thread-ids)]
    (is (= [[:enter :a]
            [:enter :b]
            [:enter :c]
            [:enter :d]]
           trace))
    (is (= 2
           (-> thread-ids distinct count)))))

(deftest termination
  (let [context (chain/terminate-when {} (fn [ctx]
                                           (some #{[:enter :b]} (::trace ctx))))
        actual (execute (enqueue context
                                 [(tracer :a)
                                  (tracer :b)
                                  (tracer :c)]))
        expected-trace [[:enter :a] [:enter :b] [:leave :b] [:leave :a]]]
    (is (= 1 (count (:io.pedestal.interceptor.chain/terminators actual))))
    (is (every? fn? (:io.pedestal.interceptor.chain/terminators actual)))
    (is (= (::trace actual) expected-trace))
    (is (= (::trace (execute-only context :enter [(tracer :a)
                                                  (tracer :b)
                                                  (tracer :c)]))
           [[:enter :a] [:enter :b]]))))

(defaround around-interceptor
  "An interceptor that does the around pattern."
  ([context] (assoc context :around :enter))
  ([context] (assoc context :around :leave)))

(deftest test-around-interceptor
  (is (interceptor/interceptor? around-interceptor)
      "defaround creates an interceptor.")
  ;(is (= true (:interceptor (meta #'around-interceptor)))
  ;    "The defined interceptor is tagged as an interceptor in metadata.")
  (is (= :enter (-> {}
                    ((:enter around-interceptor))
                    :around))
      "The first fn body gets executed during the enter stage.")
  (is (= :leave (-> {}
                    ((:leave around-interceptor))
                    :around))
      "The second fn body gets executed during the leave stage."))

(defmiddleware middleware-interceptor
  "An interceptor that does the middleware pattern."
  ([request] (assoc request :middleware :enter))
  ([response] (assoc response :middleware :leave)))

(deftest test-middleware-interceptor
  (is (interceptor/interceptor? middleware-interceptor)
      "defmiddleware creates an interceptor.")
  ;(is (= true (:interceptor (meta #'middleware-interceptor)))
  ;    "The defined interceptor is tagged as an interceptor in metadata.")
  (is (= :enter (-> {:request {}}
                    ((:enter middleware-interceptor))
                    :request
                    :middleware))
      "The first fn body gets executed with the value of request during the enter stage.")
  (is (= :leave (-> {:response {}}
                    ((:leave middleware-interceptor))
                    :response
                    :middleware))))
