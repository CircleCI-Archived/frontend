(ns frontend.stripe
  (:require [frontend.async :refer [put!]]
            [frontend.env :as env]
            [frontend.utils :as utils :include-macros true]
            [goog.net.jsloader]))

;; We may want to add StripeCheckout to externs to avoid all of the aget noise.

(def stripe-key
  (if (env/production?)
    "foo"
    "bar"))

(def checkout-defaults {:key stripe-key
                        :name "CircleCI"
                        :address false
                        :panelLabel "Pay"})

(defn open-checkout
  "Opens the StripeCheckout modal, then puts the result of the token callback into channel"
  [{:keys [price description] :as checkout-args} channel]
  (let [checkout (aget js/window "StripeCheckout")
        args (merge checkout-defaults
                    checkout-args
                    ;; Stripe will always return a token, even if the card is invalid.
                    ;; We'll propagate the error from the backend when we make the next API call
                    {:token #(put! channel [:stripe-checkout-succeeded (utils/js->clj-kw %)])
                     ;; Stripe will fire both callbacks on successful submit. This makes sure that
                     ;; the closed event fires last. The timeout by itself is enough, but we'll
                     ;; add 300ms for good measure.
                     :closed (fn [] (js/setTimeout #(put! channel [:stripe-checkout-closed]) 300))})]
    ((aget checkout "open") (clj->js args))))

(defn checkout-loaded?
  "Tests to see if the StripeCheckout javascript has loaded"
  []
  (aget js/window "StripeCheckout"))

(defn load-checkout [channel]
  (-> (goog.net.jsloader.load "https://checkout.stripe.com/v2/checkout.js")
      (.addCallback #(put! channel [:stripe-checkout-loaded :success]))))
