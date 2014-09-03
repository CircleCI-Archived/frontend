(ns frontend.utils.docs
  (:require [clojure.string :as string]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true :refer [defrender]]
            [goog.string :as gstring]
            goog.string.format
            [frontend.state :as state]
            [om.core :as om :include-macros true])
  (:require-macros [dommy.macros :refer [node]]))

(defn include-article [template-name]
  ((aget (aget js/window "HAML") template-name)))

(defn render-haml-template [template-name & [args]]
  [:div {:dangerouslySetInnerHTML
         #js {"__html"  ((aget (aget js/window "HAML") template-name)
                         (clj->js (merge {"include_article" include-article} args)))}}])

(defn api-curl [endpoint]
  (let [curl-args (if-not (= (:method endpoint) "GET")
                    (str "-X " (:method endpoint) " ")
                    "")
        curl-params (if-let [params (:params endpoint)]
                      (->> params
                           (map #(str (:name %) "=" (:example %)))
                           (string/join "&")
                           (str "&"))
                      "")]
    (gstring/format "curl %shttps://circleci.com%s?circle-token=:token%s"
                    curl-args (:url endpoint) curl-params)))

(defn hiccup->str [hiccup]
  (.-innerHTML (node [:div hiccup])))

(defn api-endpoint-filter [endpoint]
  (hiccup->str
   [:div
    [:p (:description endpoint)]
    [:h4 "Method"]
    [:p (:method endpoint)]
    (when-let [params (:params endpoint)]
      [:div [:h4 "Optional parameters"]
       [:table.table
        [:thead
         [:tr
          [:th "Parameter"] [:th "Description"]]]
        [:tbody
         (for [param params]
           [:tr
            [:td (:name param)]
            [:td (:description param)]])]]])
    [:h4 "Example call"]
    [:pre [:code (api-curl endpoint)]]
    [:h4 "Example response"]
    [:pre [:code (:response endpoint)]]
    (when (:try_it endpoint)
      [:p [:a {:href (str "https://circleci.com" (:url endpoint)) :target "_blank"}
           "Try it in your browser"]])]))

(defn code-list-filter [versions]
  (hiccup->str
   [:ul (for [version versions]
          [:li [:code (if-let [name (:name version)] name version)]])]))

(defn replace-variables [html]
  (string/replace html #"\{\{\s*([^\s]*)\s*(?:\|\s*([\w-]*)\s*)?\}\}"
                  (fn [s m1 m2]
                    (let [[name & path] (string/split m1 #"\.")
                          var (case name
                                "versions" (aget js/window "CI" "Versions")
                                "api_data" (aget js/window "circle_api_data"))
                          val (apply aget var path)
                          filter (case m2
                                   "code-list" code-list-filter
                                   "api-endpoint" api-endpoint-filter
                                   identity)]
                      (filter (utils/js->clj-kw val))))))

(defn replace-asset-paths [html]
  (string/replace html #"\(asset:/(/.*?)\)"
                  (fn [s match]
                    (str "(" (stefon/asset-path match) ")"))))

(defn render-markdown [input]
  (when input
    (-> input
        replace-asset-paths
        replace-variables
        js/marked)))

(defn new-context []
  (let [context (js/Object.)]
    (aset context "include_article" include-article)
    context))

(defn ->template-kw [template-name]
  (keyword (string/replace (name template-name) "_" "-")))

(defn article? [template-fn]
  (re-find #"this.title =" (str template-fn)))

(defn article-info [template-name template-fn]
  (let [context (new-context) ;; create an object that we can pass to the template-fn
        _ (template-fn context)       ;; writes properties into the context
        props (utils/js->clj-kw context)
        children (map ->template-kw (or (:children props) []))
        title (:title props)
        short-title (or (:short_title props) title)]
    {:url (str "/docs/" (string/replace template-name "_" "-"))
     :slug template-name
     :title title
     :sort_title short-title
     :children children
     :lastUpdated (:lastUpdated props)
     :category (:category props)}))

(defn update-child-counts [{:keys [children title ] short-title :sort_title :as info}]
  (let [child-count (count children)
        has-children (seq children)
        title-with-count (if has-children (gstring/format "%s (%d)" title child-count) title)
        short-title-with-count (if has-children (gstring/format "%s (%d)" short-title child-count) short-title)]
    (assoc info :title_with_child_count title-with-count :short_title_with_child_count short-title-with-count)))

(defn update-children [docs]
  (reduce (fn [acc [template-name article-info]]
            (if (seq (:children article-info))
              (update-in acc [template-name :children] (fn [children]
                                                         ((apply juxt children) docs)))
              acc))
          docs docs))

(defn find-all-docs*
  "process all HAML templates, and picks the articles based on their contents
  (they write into the context, and we check for that)
   Returns an hash map of article subpages to articles."
  []
  (let [docs (reduce (fn [acc [template-name template-fn]]
                       (if (article? template-fn)
                         (let [subpage (->template-kw template-name)]
                           (assoc acc subpage (update-child-counts (article-info template-name template-fn))))
                         acc))
                     {} (js->clj (aget js/window "HAML")))]
    (update-children docs)))

(def find-all-docs (memoize find-all-docs*))

(defn maybe-rewrite-token [token]
  (case token
    "common-problems#intro" ""
    "common-problems#file-ordering" "file-ordering"
    "common-problems#missing-log-dir" "missing-log-dir"
    "common-problems#missing-file" "missing-file"
    "common-problems#time-day" "time-day"
    "common-problems#time-seconds" "time-seconds"
    "common-problems#requires-admin" "requires-admin"
    "common-problems#oom" "oom"
    "common-problems#wrong-ruby-version" "wrong-ruby-version"
    "common-problems#dont-run" "dont-run"
    "common-problems#git-bundle-install" "git-bundle-install"
    "common-problems#git-pip-install" "git-pip-install"
    "common-problems#wrong-commands" "wrong-commands"
    "common-problems#bundler-latest" "bundler-latest"
    "common-problems#capybara-timeout" "capybara-timeout"
    "common-problems#clojure-12" "clojure-12"
    "common-problems" "troubleshooting"

    "faq" ""
    "faq#permissions" "permissions"
    "faq#what-happens" "what-happens"
    "faq#look-at-code" "look-at_code"
    "faq#parallelism" "parallelism"
    "faq#versions" "environment"
    "faq#external-resources" "external-resources"
    "faq#cant-follow" "cant-follow"

    "wrong-commands" "wrong-ruby-commands"
    "configure-php" "language-php"
    "reference-api" "api"
    "reference-api#build" "api#build"
    token))
