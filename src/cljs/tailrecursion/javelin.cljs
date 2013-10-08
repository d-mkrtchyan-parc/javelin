;; Copyright (c) Alan Dipert and Micha Niskin. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns tailrecursion.javelin
  (:require
   [alandipert.desiderata :as d]
   [tailrecursion.priority-map :refer [priority-map]]))

;; specials ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn no-supported
  [spec-form]
  #(throw (js/Error. (str spec-form " is not supported in cell formulas"))))

(defn if* 
  ([pred consequent] (if pred consequent))
  ([pred consequent alternative] (if pred consequent alternative)))

(def do*         (fn [& body] (last body)))
(def throw*      #(if (string? %) (js/Error. %) %))

(def def*        (no-supported "def"))
(def loop**      (no-supported "loop*"))
(def letfn**     (no-supported "letfn*"))
(def try**       (no-supported "try*"))
(def recur*      (no-supported "recur"))
(def ns*         (no-supported "ns"))
(def deftype**   (no-supported "deftype*"))
(def defrecord** (no-supported "defrecord*"))
(def &*          (no-supported "&"))

(defn new*
  ([class] (new class))
  ([class a] (new class a))
  ([class a b] (new class a b))
  ([class a b c] (new class a b c))
  ([class a b c d] (new class a b c d))
  ([class a b c d e] (new class a b c d e))
  ([class a b c d e f] (new class a b c d e f))
  ([class a b c d e f g] (new class a b c d e f g))
  ([class a b c d e f g h] (new class a b c d e f g h))
  ([class a b c d e f g h i] (new class a b c d e f g h i))
  ([class a b c d e f g h i & more] (no-supported "new w/more than 10 args")))

;; javelin ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare Cell cell? input)

(def  last-rank     (atom 0))
(defn next-rank [ ] (swap! last-rank inc))
(defn deref*    [x] (if (cell? x) @x x))

(defn propagate! [cell]
  (loop [queue (priority-map cell (.-rank cell))]
    (when (seq queue)
      (let [next      (key (peek queue))
            value     ((.-thunk next))
            continue? (not= value (.-prev next))
            reducer   #(assoc %1 %2 (.-rank %2))
            siblings  (pop queue)
            children  (.-sinks next)]
        (if continue? (set! (.-prev next) value))
        (recur (if continue? (reduce reducer siblings children) siblings))))))

(defn set-formula! [this & [f sources]]
  (doseq [source (filter cell? (.-sources this))]
    (set! (.-sinks source) (disj (.-sinks source) this)))
  (set! (.-sources this) (if f (conj (vec sources) f) (vec sources)))
  (doseq [source (filter cell? (.-sources this))]
    (set! (.-sinks source) (conj (.-sinks source) this))
    (if (> (.-rank source) (.-rank this))
      (doseq [dep (d/bf-seq identity #(.-sinks %) source)]
        (set! (.-rank dep) (next-rank)))))
  (let [compute   #(apply (deref* (peek %)) (map deref* (pop %)))
        thunk     #(set! (.-state this) (compute (.-sources this)))
        err-mesg  "formula cell can't be updated via swap! or reset!"
        watch-err (fn [_ _ _ _] (throw (js/Error. err-mesg)))
        watch-ok  (fn [_ cell _ _] (propagate! cell))]
    (-remove-watch this ::propagate)
    (-add-watch this ::propagate (if f watch-err watch-ok))
    (set! (.-thunk this) (if f thunk #(deref this)))
    (doto this propagate!)))

(deftype Cell [meta state rank prev sources sinks thunk watches]
  cljs.core/IMeta
  (-meta [this] meta)

  cljs.core/IDeref
  (-deref [this] (.-state this))

  cljs.core/IWatchable
  (-notify-watches [this oldval newval]
    (doseq [[key f] watches]
      (f key this oldval newval)))
  (-add-watch [this key f]
    (set! (.-watches this) (assoc watches key f)))
  (-remove-watch [this key]
    (set! (.-watches this) (dissoc watches key))))

(defn cell?  [c] (= (type c) Cell))
(defn input* [x] (if (cell? x) x (input x)))
(defn input  [x] (set-formula! (Cell. {} x (next-rank) x [] #{} nil {})))
(defn lift   [f] (fn [& sources] (set-formula! (input ::none) f sources)))
