(ns frontend.favicon
  (:refer-clojure :exclude [reset!])
  (:require [frontend.utils :as utils :include-macros true]))

(defn favicon-link []
  (.querySelector js/document "link[rel='icon']"))

(defn get-color []
  (last (re-find #"favicon-([^\.]+)\.ico" (.getAttribute (favicon-link) "href"))))

(defn set-color! [color]
  (utils/swallow-errors
   (if (= color (get-color))
     (utils/mlog "Not setting favicon to same color")
     (.setAttribute (favicon-link) "href" (utils/cdn-path (str "/favicon-" color ".ico?v=28"))))))

(defn reset! []
  ;; This seemed clever at the time, undefined is the default dark blue
  (set-color! "undefined"))
