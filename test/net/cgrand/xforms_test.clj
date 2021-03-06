(ns net.cgrand.xforms-test
  (:require [clojure.test :refer :all]
            [net.cgrand.xforms :as x]))

(defn trial
  "A transducing context for testing that transducers are well-behaved towards
  linear use of the accumulator, init, completion and handling of reduced values.
  A \"poisonous\" reducing function rf is passed to the transducer.
  n is the number of calls to rf before it returns a reduced.
  accs is a collection of successive return values for rf."
  ([xform n coll]
    (trial xform n (repeatedly #(Object.)) coll))
  ([xform n accs coll]
    (let [vaccs (volatile! accs)
          vstate (volatile! {:n n :acc (first @vaccs) :state :init})
          check-acc (fn [acc]
                      (when (reduced? acc)
                        (throw (ex-info "Called with reduced accumulator" (assoc @vstate :actual-acc acc))))
                      (when-not (identical? acc (:acc @vstate))
                        (throw (ex-info "Called with an unexpected accumulator (either non-linear or out of thin air)" (assoc @vstate :actual-acc acc)))))
          rf (fn
               ([]
                 (when-not (= :init (:state @vstate))
                   (throw (ex-info "Init arity called on a started or completed rf." @vstate)))
                 (:acc (vswap! vstate assoc :state :started)))
               ([acc]
                 (when (= :completed (:state @vstate))
                   (throw (ex-info "Completion arity called on an already completed rf." @vstate)))
                 (check-acc acc)
                 (:acc (vswap! vstate assoc :state :completed :acc (first (vswap! vaccs next)))))
               ([acc x]
                 (when (= :completed (:state @vstate))
                   (throw (ex-info "Step arity called on an already completed rf." @vstate)))
                 (when (= :reduced (:state @vstate))
                   (throw (ex-info "Step arity called on a reduced rf." @vstate)))
                 (check-acc acc)
                 (let [n (:n @vstate)]
                   (let [acc (first (vswap! vaccs next))]
                     (if (pos? n)
                      (:acc (vswap! vstate assoc :acc acc :n (dec n)))
                      (reduced (:acc (vswap! vstate assoc :acc acc :status :reduced))))))))
          res (transduce xform rf coll)]
      (check-acc res)
      (when-not (= :completed (:state @vstate))
        (throw (ex-info "Completion arity never called" @vstate)))
      true)))

(deftest proper-rf-usage
  (testing "Ensuring that reducing functions returned by transducers are well-behaved."
    (is (trial (x/by-key odd? identity)
          4 (range 16)))
    (is (trial (x/by-key odd? identity nil identity)
          4 (range 16)))
    (is (trial (x/by-key odd? (take 2))
          8 (range 16)))
    (is (trial (x/by-key odd? identity)
          8 (range 2)))
    (is (trial (x/partition 3 identity)
          4 (range 16)))
    (is (trial (x/partition 3 (take 2))
          8 (range 16)))
    (is (trial (x/partition 3 (take 2))
          8 (range 2)))
    (is (trial (x/into [])
          4 (range 16)))
    (is (trial (x/for [x % y (range x)] [x y])
          4 (range 16)))
    (is (trial (x/reduce +)
          4 (range 16)))
    (is (trial (x/pad 2 (repeat :pad))
          4 (range 16)))
    (is (trial (x/pad 8 (repeat :pad))
          4 (range 16)))))

(deftest window-by-time
  (is (= (into 
           []
           (x/window-by-time :ts 4
             (fn 
               ([] clojure.lang.PersistentQueue/EMPTY)
               ([q] (vec q))
               ([q x] (conj q x)))
             (fn [q _] (pop q)))
           (map (fn [x] {:ts x}) (concat (range 0 2 0.5) (range 3 5 0.25))))
         [[{:ts 0}] ; t =  0
  [{:ts 0}] ; t =  0.25
  [{:ts 0} {:ts 0.5}] ; t =  0.5
  [{:ts 0} {:ts 0.5}] ; t =  0.75
  [{:ts 0.5} {:ts 1.0}] ; t =  1.0
  [{:ts 0.5} {:ts 1.0}] ; t =  1.25
  [{:ts 1.0} {:ts 1.5}] ; t =  1.5
  [{:ts 1.0} {:ts 1.5}] ; t =  1.75
  [{:ts 1.5}] ; t =  2.0
  [{:ts 1.5}] ; t =  2.25
  [] ; t =  2.5
  [] ; t =  2.75
  [{:ts 3}] ; t =  3.0
  [{:ts 3} {:ts 3.25}] ; t =  3.25
  [{:ts 3} {:ts 3.25} {:ts 3.5}] ; t =  3.5
  [{:ts 3} {:ts 3.25} {:ts 3.5} {:ts 3.75}] ; t =  3.75
  [{:ts 3.25} {:ts 3.5} {:ts 3.75} {:ts 4.0}] ; t =  4.0
  [{:ts 3.5} {:ts 3.75} {:ts 4.0} {:ts 4.25}] ; t =  4.25
  [{:ts 3.75} {:ts 4.0} {:ts 4.25} {:ts 4.5}] ; t =  4.5
  [{:ts 4.0} {:ts 4.25} {:ts 4.5} {:ts 4.75}]]))) ; t =  4.75