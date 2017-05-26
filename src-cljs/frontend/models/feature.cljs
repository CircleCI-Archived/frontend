(ns frontend.models.feature
  "Functions related to enabling and disabling features."
  (:require [goog.net.cookies :as cookies]
            [frontend.utils.launchdarkly :as ld]
            [schema.core :as s])
  (:import goog.Uri))

(def feature-manifest
  "Feature manifest intended to be fall back in environments where LaunchDarkly
  isn't available
  In an ideal world, when a feature launches to 100%, the code will be updated
  and old behavior branches and conditionals get removed.  This manifest intends
  to be a stop-gap until it's removed"
  {})

(defn- feature-flag-value-true? [value]
  (= value "true"))

(defn- feature-flag-key-name [feature]
  (str "feature-" (name feature)))

(defn- get-in-query-string [feature]
  (-> js/location
      str
      goog.Uri.parse
      .getQueryData
      (.get (feature-flag-key-name feature))))

(defn set-in-query-string? [feature]
  (some? (get-in-query-string feature)))

(defn enabled-in-query-string? [feature]
  (feature-flag-value-true? (get-in-query-string feature)))

(defn- get-in-cookie [feature]
  (-> feature
      feature-flag-key-name
      cookies/get))

(defn enabled-in-cookie? [feature]
  (-> (get-in-cookie feature)
      feature-flag-value-true?))

;; feature cookies should be basically permanent, so make them last for 10 years
(def feature-cookie-max-age (* 60 60 24 365 10))

;; export so we can set this using javascript in production
(defn ^:export enable-in-cookie [feature]
  (cookies/set (feature-flag-key-name feature) "true" feature-cookie-max-age))

;; export so we can set this using javascript in production
(defn ^:export disable-in-cookie [feature]
  (cookies/set (feature-flag-key-name feature) "false" feature-cookie-max-age))

(defn ^:dynamic enabled?
  "If a feature is enabled or disabled in the query string, use that
  value, otherwise look in a cookie for the feature. Returns false by
  default."
  [feature]
  (let [feature-name (name feature)]
    (cond
      (set-in-query-string? feature-name) (enabled-in-query-string? feature-name)
      (ld/exists? feature-name) (ld/feature-on? feature-name)
      (contains? feature-manifest feature-name) (get feature-manifest feature-name)
      (some? (get-in-cookie feature-name)) (enabled-in-cookie? feature-name))))

(defn ab-test-treatment-map
  ([]
   {:github-student-pack {true :in-student-pack
                          false :not-in-student-pack}
    :junit-ab-test {true :junit-button
                    false :junit-banner}
    :setup-docs-ab-test {true :setup-docs-modal
                         false :setup-docs-banner}})

  ([user]
   (merge (ab-test-treatment-map)
          {;;this is a holding place for test treatments that depend on user properties
           })))

(defn ab-test-treatments
  "Return a map of {:test :treatment}, where :test is the test we are running and
  :treatment is the treatment this user sees."
  [treatment-map]
  (->> treatment-map
       keys
       (map (fn [feature]
              [feature (get-in treatment-map [feature (-> feature enabled?  boolean)])]))
       (into {})))

(defn ab-test-treatment
  ([feature]
   (-> (ab-test-treatment-map)
       (ab-test-treatments)
       (get feature)))
  ([feature user]
   (-> (ab-test-treatment-map user)
       (ab-test-treatments)
       (get feature))))
