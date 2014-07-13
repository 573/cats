;; Copyright (c) 2014, Andrey Antukh
;; Copyright (c) 2014, Alejandro Gómez
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;;
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns cats.core
  "Category Theory abstractions for Clojure"
  (:require [cats.protocols :as p])
  #+cljs
  (:require-macros [cats.core :refer (with-context mlet)])
  (:refer-clojure :exclude [when unless filter sequence]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *m-context* nil)

#+clj
(defmacro with-context
  [ctx & body]
  `(binding [*m-context* ~ctx]
     ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Context-aware funcionts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn pure
  "Given any value v, return it wrapped in
  default/effect free context.

  This is multiarity function that with arity pure/1
  it uses the dynamic scope to resolve the current
  context. With pure/2, you can force a specific context
  value."
  ([v]
     #+clj
     (when-not (bound? #'*m-context*)
       (throw (IllegalArgumentException.
               "You are using return/pure function without context.")))
     (p/pure *m-context* v))
  ([app v]
     (p/pure app v)))

(defn return
  "This is a monad version of pure."
  ([v]
     (p/mreturn *m-context* v))
  ([m v]
     (p/mreturn m v)))

(defn bind
  "Given a value inside monadic context mv and any function,
  applies a function to value of mv."
  [mv f]
  (if (not (nil? *m-context*))
    (p/mbind *m-context* mv f)
    (with-context (p/get-context mv)
      (bind mv f))))

(defn mzero
  []
  (p/mzero *m-context*))

(defn mplus
  [& mvs]
  {:pre [(not (empty? mvs))]}
  (let [ctx (if (nil? *m-context*)
              (p/get-context (first mvs))
              *m-context*)]
    (reduce (partial p/mplus ctx)
            mvs)))

(defn guard
  [b]
  (if b
    (return nil)
    (mzero)))

(defn join
  "Remove one level of monadic structure."
  [mv]
  (bind mv identity))

(defn fmap
  "Apply a function f to the value inside functor's fv
  preserving the context type."
  [f fv]
  (let [ctx (if (nil? *m-context*)
              (p/get-context fv)
              *m-context*)]
    (p/fmap ctx f fv)))

(defn fapply
  "Given function inside af's conext and value inside
  av's context, applies the function to value and return
  a result wrapped in context of same type of av context."
  [af av]
  (let [ctx (if (nil? *m-context*)
              (p/get-context av)
              *m-context*)]
    (p/fapply ctx af av)))

(defn when
  "If the expression is true, returns the monadic value.

  Otherwise, yields nil in a monadic context."
  [b mv]
  (let [ctx (if (nil? *m-context*)
              (p/get-context mv)
              *m-context*)]
    (if b
      mv
      (return ctx nil))))

(defn unless
  "If the expression is false, returns the monadic value.

  Otherwise, yields nil in a monadic context."
  [b mv]
  (let [ctx (if (nil? *m-context*)
              (p/get-context mv)
              *m-context*)]
    (if (not b)
      mv
      (return ctx nil))))

(defn lift
  ; TODO: docstring
  ([mv]
     (lift *m-context* mv))
  ([m mv]
     (p/lift m mv)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Monadic Let Macro
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#+clj
(defmacro mlet
  [bindings & body]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (throw (IllegalArgumentException. "bindings has to be a vector with even number of elements.")))
  (if (seq bindings)
    (let [l (get bindings 0)
          r (get bindings 1)
          next-mlet `(mlet ~(subvec bindings 2) ~@body)]
      (condp = l
        :let `(let ~r ~next-mlet)

        :when `(bind (guard ~r)
                     (fn [~(gensym)]
                       ~next-mlet))
        `(bind ~r
               (fn [~l]
                 ~next-mlet))))
    `(do ~@body)))

#+clj
(defmacro lift-m
  "Lifts a function with the given fixed number of arguments to a
  monadic context.

    (require '[cats.monad.maybe :as maybe])
    (require '[cats.core :as m])

    (def monad+ (m/lift-m 2 +))

    (monad+ (maybe/just 1) (maybe/just 2))
    ;=> <Just [3]>

    (monad+ (maybe/just 1) (maybe/nothing))
    ;=> <Nothing>

    (monad+ [0 2 4] [1 2])
    ;=> [1 2 3 4 5 6]
  "
  [n f]
  (let [val-syms (repeatedly n gensym)
        mval-syms (repeatedly n gensym)
        mlet-bindings (interleave val-syms mval-syms)]
    `(fn [~@mval-syms]
       (mlet [~@mlet-bindings]
         (return (~f ~@val-syms))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sequences.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sequence
  "Given a non-empty collection of monadic values, collect
  their values in a vector returned in the monadic context.

    (require '[cats.monad.maybe :as maybe])
    (require '[cats.core :as m])

    (m/sequence [(maybe/just 2) (maybe/just 3)])
    ;=> <Just [[2, 3]]>

    (m/sequence [(maybe/nothing) (maybe/just 3)])
    ;=> <Nothing>
  "
  [mvs]
  {:pre [(not-empty mvs)]}
  (let [ctx (if (nil? *m-context*)
              (p/get-context (first mvs))
              *m-context*)]
  (with-context ctx
    (reduce (fn [mvs mv]
               (mlet [v mv
                      vs mvs]
                     (return (cons v vs))))
            (return '())
            (reverse mvs)))))

(defn mapseq
   "Given a function that takes a value and puts it into a
   monadic context, map it into the given collection
   calling sequence on the results.

     (require '[cats.monad.maybe :as maybe])
     (require '[cats.core :as m])

     (m/mapseq maybe/just [2 3])
     ;=> <Just [[2 3]]>

     (m/mapseq (fn [v]
                  (if (odd? v)
                    (maybe/just v)
                    (maybe/nothing)))
                 [1 2])
     ;=> <Nothing>
  "
  [mf coll]
  (sequence (map mf coll)))

(defn forseq
  "Same as mapseq but with the arguments in reverse order.

    (require '[cats.monad.maybe :as maybe])
    (require '[cats.core :as m])

    (m/forseq [2 3] maybe/just)
    ;=> <Just [[2 3]]>

    (m/forseq [1 2]
              (fn [v]
                (if (odd? v)
                  (maybe/just v)
                  (maybe/nothing))))
    ;=> <Nothing>
  "
  [vs mf]
  (mapseq mf vs))

(defn filter
  "Applies a predicate to a value in a `MonadZero` instance,
  returning the identity element when the predicate yields false.

  Otherwise, returns the instance unchanged.

    (require '[cats.monad.moaybe :as maybe])
    (require '[cats.core :as m])

    (m/filter (partial < 2) (maybe/just 3))
    ;=> <Just [3]>

    (m/filter (partial < 4) (maybe/just 3))
    ;=> <Nothing>
  "
  [p mv]
  (mlet [v mv
         :when (p v)]
        (return v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Haskell-style aliases and util functions.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn <$>
  "Alias of fmap."
  ([f]
     (fn [fv]
       (fmap f fv)))
  ([f fv]
     (fmap f fv)))

(defn <*>
  "Performs a Haskell-style left-associative fapply."
  ([af av]
     (fapply af av))
  ([af av & avs]
     (reduce fapply af (cons av avs))))

(defn >>=
  "Performs a Haskell-style left-associative bind.

  Example:
    (>>= (just 1) (comp just inc) (comp just inc))
    ;=> #<Just [3]>
  "
  ([mv f]
     (bind mv f))
  ([mv f & fs]
     (reduce bind mv (cons f fs))))

(defn =<<
  "Same as the two argument version of `>>=` but with the
  arguments interchanged."
  [f mv]
  (>>= mv f))

(defn >=>
  "Left-to-right composition of monads."
  [mf mg x]
  (mlet [a (mf x)
         b (mg a)]
       (return b)))

(defn <=<
  "Right-to-left composition of monads.
  Same as `>=>` with its first two arguments flipped."
  [mg mf x]
  (mlet [a (mf x)
         b (mg a)]
        (return b)))
