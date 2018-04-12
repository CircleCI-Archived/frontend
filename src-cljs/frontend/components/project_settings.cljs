(ns frontend.components.project-settings
  (:require [ajax.core :as clj-ajax]
            [cljs-time.core :as time]
            [cljs-time.format :as time-format]
            [clojure.string :as string]
            [frontend.analytics.track :as analytics-track]
            [frontend.api :as api]
            [frontend.async :refer [raise!]]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.components.inputs :as inputs]
            [frontend.components.pieces.button :as button]
            [frontend.components.pieces.card :as card]
            [frontend.components.pieces.confirmation-button :as confirmation-button]
            [frontend.components.pieces.dropdown :as dropdown]
            [frontend.components.pieces.empty-state :as empty-state]
            [frontend.components.pieces.form :as form]
            [frontend.components.pieces.icon :as icon]
            [frontend.components.pieces.modal :as modal]
            [frontend.components.pieces.spinner :refer [spinner]]
            [frontend.components.pieces.table :as table]
            [frontend.components.project.common :as project-common]
            [frontend.config :as config]
            [frontend.datetime :as datetime]
            [frontend.models.feature :as feature]
            [frontend.models.plan :as plan-model]
            [frontend.models.project :as project-model]
            [frontend.models.user :as user-model]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils.ajax :as ajax]
            [frontend.utils :as utils :refer-macros [component element html]]
            [frontend.utils.bitbucket :as bb-utils]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.html :refer [hiccup->html-str]]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs :as vcs]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.utils.slack :as slack]
            [goog.crypt.base64 :as base64]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]))

(defn- remove-action-button
  "Renders a \"Remove\" action button suitable for a settings table row which
  presents a confirmation dialog before actually removing the row.

  :confirmation-question - The content of the dialog. Should ask the user if
                           they're sure they want to remove the row, clearly
                           describing what the user is about to remove.

  :remove-fn             - The function which will actually remove the row once
                           confirmed."
  [{:keys [confirmation-question remove-fn]} owner]
  (confirmation-button/confirmation-button
    {:confirmation-text confirmation-question
     :action-text "Remove"
     :action-fn remove-fn}
    owner))

(defn branch-names [project-data]
  (map (comp gstring/urlDecode name) (keys (:branches (:project project-data)))))

(defn branch-picker [project-data owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/typeahead
       "#branch-picker-typeahead-hack"
       {:source (branch-names project-data)
        :updater (fn [branch]
                   (raise! owner [:edited-input {:path (conj state/inputs-path :settings-branch) :value branch}])
                   branch)}))
    om/IRender
    (render [_]
      (let [{:keys [button-text channel-message channel-args]
             :or {button-text "Start a build" channel-message :started-edit-settings-build}} opts
             project (:project project-data)
             project-id (project-model/id project)
             default-branch (:default_branch project)
             settings-branch (get (inputs/get-inputs-from-app-state owner) :settings-branch default-branch)]
        (html
         [:form
          [:input {:name "branch"
                   :id "branch-picker-typeahead-hack"
                   :required true
                   :type "text"
                   :value (str settings-branch)
                   :on-change #(utils/edit-input owner (conj state/inputs-path :settings-branch) %)}]
          [:label {:placeholder "Test settings on..."}]
          (forms/managed-button
           [:input
            {:value button-text
             :on-click #(raise! owner [channel-message (merge {:project-id project-id} channel-args)])
             :data-loading-text "Starting..."
             :data-success-text "Started..."
             :type "submit"}])])))))

(defn overview [project-data owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})

    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [project (:project project-data)
            username (:username project)
            reponame (:reponame project)
            vcs-url (:vcs_url project)
            project-id (project-model/id project)]
        (html
         [:section
          [:article
           [:legend "Project Overview"]
           (card/collection
             (concat
               [(card/titled
                  {:title (str "How to configure " (vcs-url/project-name (get-in project-data [:project :vcs_url])))}
                  (html
                    [:div
                     [:b "Option 1"]
                     [:p "Do nothing! CircleCI infers many settings automatically. Works great for Ruby, Python, NodeJS, Java and Clojure. However, if it needs tweaks or doesn't work, see below."]
                     [:b "Option 2"]
                     [:p
                      "Override inferred settings and add new test commands "
                      [:a {:href "#setup"} "through the web UI"]
                      ". This works great for prototyping changes."]
                     [:b "Option 3"]
                     [:p
                      "Override all settings via a "
                      [:a {:href "https://circleci.com/docs/2.0/configuration-reference/"} "config.yml file"]
                      " in your repo. Very powerful."]]))]
               (if (:following project)
                 [(card/titled
                    {:title (str "You're following " (vcs-url/project-name vcs-url))}
                    (html
                      [:div
                       [:p
                        "We'll keep an eye on this and update you with personalized build emails and notifications."
                        [:br]
                        "You can update your notifications from your "
                        [:a {:href "/account"} "user settings"]
                        "."]
                       (when show-modal?
                         (let [close-fn #(om/set-state! owner :show-modal? false)
                               ;; NB: "component" here is used in the analytics
                               ;; sense; it's a string describing a UI element,
                               ;; not a React component.
                               cancel-fn-for-component
                               (fn [component-clicked]
                                 #(do
                                    (close-fn)
                                    ((om/get-shared owner :track-event) {:event-type :stop-building-modal-dismissed
                                                                         :properties {:component-clicked component-clicked}})))]
                           (modal/modal-dialog
                             {:title (gstring/format "Stop building %s/%s on CircleCI?" username reponame)
                              :body (html
                                      [:div
                                       [:p
                                        (gstring/format
                                          "When you stop building %s on CircleCI, we will stop all builds and unfollow all teammates who are currently following the project."
                                          reponame)]])
                              :actions [(button/button {:on-click (cancel-fn-for-component "cancel-button")} "Cancel")
                                        (button/managed-button
                                          {:on-click #(do
                                                        (raise! owner [:stopped-building-project {:vcs-url vcs-url
                                                                                                  :project-id project-id
                                                                                                  :on-success close-fn}])
                                                        ((om/get-shared owner :track-event) {:event-type :stop-building-clicked}))
                                           :kind :danger
                                           :loading-text "Stopping Builds..."
                                           :success-text "Builds Stopped"}
                                          "Stop Building")]
                              ;; The :close-fn is used for the modal X and when
                              ;; the user clicks outside the modal to dismiss
                              ;; it (and potentially anywhere else the modal
                              ;; needs it). Therefore, we can't assign a
                              ;; meaningful component here. We can at least set it
                              ;; to nil so we don't lie.
                              :close-fn (cancel-fn-for-component nil)})))

                       (button/managed-button
                         {:on-click #(raise! owner [:unfollowed-project {:vcs-url vcs-url :project-id project-id}])
                          :loading-text "Unfollowing..."
                          :kind :primary}
                         "Unfollow Project")]))

                  (card/titled {:title (str "You're building " (vcs-url/project-name vcs-url))}
                               (button/button {:on-click #(om/set-state! owner :show-modal? true)
                                               :kind :danger}
                                              "Stop Building"))]
                 [(card/titled
                    {:title "You're not following this project"}
                    (html
                      [:div
                       [:p "We can't update you with personalized build emails and notifications unless you follow this project. "
                        "Projects are only tested if they have a follower."]
                       (button/managed-button
                         {:on-click #(raise! owner [:followed-project {:vcs-url vcs-url :project-id project-id}])
                          :kind :primary
                          :loading-text "Following..."}
                         "Follow Project")]))])))]])))))


(defn build-environment [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            plan (:plan project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))
            ;; This project's feature flags
            feature-flags (project-model/feature-flags project)
            describe-flag (fn [{:keys [flag title blurb]}]
                            (when (contains? (set (keys feature-flags)) flag)
                              [:li
                               [:h4 title]
                               [:p blurb]
                               [:form
                                [:ul
                                 [:li.radio
                                  [:label
                                   [:input
                                    {:type "radio"
                                     :checked (get feature-flags flag)
                                     :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                               :flag flag
                                                                                               :value true}])}]
                                   " On"]]
                                 [:li.radio
                                  [:label
                                   [:input
                                    {:type "radio"
                                     :checked (not (get feature-flags flag))
                                     :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                               :flag flag
                                                                                               :value false}])}]
                                   " Off"]]]]]))]
        (html
          [:section
           [:article
            [:legend "Build Environment"]
            [:ul
             (describe-flag {:flag :osx
                             :title "Build OS X project"
                             :blurb [:div
                                     [:p
                                      [:strong
                                       "Note: this option only works if the project builds under an OS X plan. "
                                       "Configure that option "
                                       [:a {:href (routes/v1-org-settings-path {:org (:org_name plan)
                                                                                :vcs_type (-> plan :org :vcs_type)
                                                                                :_fragment "osx-pricing"})} "here"]
                                       "."]]
                                     [:p
                                      "This option reflects whether CircleCI will run builds for this project "
                                      "against Linux-based hardware or OS X-based hardware. Please use this "
                                      "setting as an override if we have incorrectly inferred where this build should run."]]})
             (when-not (config/enterprise?)
               [:li
                [:h4 "OS to use for builds"]
                [:p [:p
                     "Select the operating system in which to run your Linux builds."
                     [:p [:strong "Please note that you need to trigger a build by pushing commits to GitHub (instead of rebuilding) to apply the new setting."]]]]
                [:form
                 [:ul
                  [:li.radio
                   [:label
                    [:input
                     {:type "radio"
                      :checked (not (get feature-flags :trusty-beta))
                      :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                :flag :trusty-beta
                                                                                :value false}])}]
                    " Ubuntu 12.04 (Precise)"]]
                  [:li.radio
                   [:label
                    [:input
                     {:type "radio"
                      :checked (get feature-flags :trusty-beta)
                      :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                :flag :trusty-beta
                                                                                :value true}])}]
                    " Ubuntu 14.04 (Trusty)"]]]]])]]])))))

(defn parallel-label-classes [{:keys [plan project] :as project-data} parallelism]
  (concat
   []
   (when (and (> parallelism 1) (project-model/osx? project)) ["disabled"])
   (when (> parallelism (project-model/buildable-parallelism plan project)) ["disabled"])
   (when (= parallelism (get-in project-data [:project :parallel])) ["selected"])
   (when (not= 0 (mod (project-model/usable-containers plan project) parallelism)) ["bad_choice"])))

(defn parallelism-tile
  "Determines what we show when they hover over the parallelism option"
  [project-data owner parallelism]
  (let [project (:project project-data)
        {{plan-org-name :name
          plan-vcs-type :vcs_type} :org
         :as plan}
        (:plan project-data)
        project-id (project-model/id project)
        add-button-text "Add More"]
    (list
     [:div.parallelism-upgrades
      (if-not (and (plan-model/in-trial? plan)
                   (not (plan-model/in-student-trial? plan)))
        (cond (and (project-model/osx? project)
                   (> parallelism 1))
              ;; OS X projects should not use parallelism. We don't have the
              ;; ability to parallelise XCode tests yet and have a limited
              ;; number of available OS X VMs. Setting parallelism for OS X
              ;; wastes VMs, reducing the number of builds we can run.
              [:div.insufficient-plan
               "OS X projects are currently limited to 1x parallelism."]

              (> parallelism (plan-model/max-parallelism plan))
              [:div.insufficient-plan
               "Your plan only allows up to "
               (plan-model/max-parallelism plan) "x parallelism."

               (button/link {:kind :primary
                             :href (common/contact-support-a-info owner)}
                            "Contact Us For More")]

              (> parallelism (project-model/buildable-parallelism plan project))
              [:div.insufficient-containers
               "Not enough containers for " parallelism "x."
               (button/link {:kind :primary
                             :href (routes/v1-org-settings-path {:org plan-org-name
                                                                 :vcs_type plan-vcs-type
                                                                 :_fragment "linux-pricing"})
                             :on-click #((om/get-shared owner :track-event) {:event-type :add-more-containers-clicked
                                                                             :properties {:button-text add-button-text}})}
                            add-button-text)])

        (when (> parallelism (project-model/buildable-parallelism plan project))
          [:div.insufficient-trial
           "Trials only come with " (plan-model/trial-containers plan) " available containers."
           (button/link {:kind :primary
                         :href (routes/v1-org-settings-path {:org plan-org-name
                                                             :vcs_type plan-vcs-type
                                                             :_fragment "linux-pricing"})}
                        "Add a Plan")]))]

     ;; Tell them to upgrade when they're using more parallelism than their plan allows,
     ;; but only on the tiles between (allowed parallelism and their current parallelism]
     (when (and (> (:parallel project) (project-model/usable-containers plan project))
                (>= (:parallel project) parallelism)
                (> parallelism (project-model/usable-containers plan project)))
       [:div.insufficient-minimum
        "Unsupported. Upgrade or lower parallelism."
        [:i.fa.fa-question-circle {:title (str "You need " parallelism " containers on your plan to use "
                                               parallelism "x parallelism.")}]
        (button/link {:kind :primary
                      :href (routes/v1-org-settings-path {:org plan-org-name
                                                          :vcs_type plan-vcs-type
                                                          :_fragment "linux-pricing"})}
                     "Upgrade Plan")]))))

(defn get-offered-parallelism
  "In enterprise, offer whatever their plan's setting is (since they cannot upgrade).
  Otherwise, offer at least 24 and up to their plan's setting."
  [project plan]
  (let [plan-parallelism (plan-model/max-parallelism plan)]
    (if (config/enterprise?)
      (max plan-parallelism (project-model/parallelism project))
      (max 24 plan-parallelism))))

(defn parallelism-picker [project-data owner]
  [:div.parallelism-picker
   (if-not (:plan project-data)
     (spinner)
     (let [plan (:plan project-data)
           project (:project project-data)
           project-id (project-model/id project)
           number-tiles (get-offered-parallelism project plan)]
       (list
        (when (:parallelism-edited project-data)
          [:div.try-out-build
           (om/build branch-picker project-data {:opts {:button-text (str "Try a build!")}})])
        [:form.parallelism-items
         (for [parallelism (range 1 (inc number-tiles))]
           [:label {:class (parallel-label-classes project-data parallelism)
                    :for (str "parallel_input_" parallelism)}
            [:span parallelism]
            (parallelism-tile project-data owner parallelism)
            [:input {:id (str "parallel_input_" parallelism)
                     :type "radio"
                     :name "parallel"
                     :value parallelism
                     :on-click #(raise! owner [:selected-project-parallelism
                                               {:project-id project-id
                                                :parallelism parallelism}])
                     :disabled (> parallelism (project-model/buildable-parallelism plan project))
                     :checked (= parallelism (:parallel project))}]])])))])

(defn trial-activation-banner [data owner]
  (reify
    om/IRender
    (render [_]
      (let [org (get-in data state/project-plan-org-name-path)
            days-left (-> (get-in data state/project-plan-path)
                          :trial_end
                          (time-format/parse)
                          (->> (time/interval (time/now)))
                          (time/in-days)
                          (+ 1))
            days-left-str (str days-left (if (> days-left 1)
                                           " days"
                                           " day"))]
        (html
          [:div.alert.offer
           [:div.text
            [:span org"'s plan has "days-left-str" left on its trial. You may want to try increasing parallelism below to get the most value out of your containers."]]])))))

(defn parallel-builds [data owner]
  (reify
    om/IRender
    (render [_]
      (let [project-data (get-in data state/project-data-path)
            plan (get-in data state/project-plan-path)]
        (html
          [:section.parallel-builds
           (when (and (plan-model/in-trial? plan)
                      (not (plan-model/in-student-trial? plan)))
             (om/build trial-activation-banner data))
           [:article
            [:div.alert.alert-info.iconified
             [:div
              [:img.alert-icon {:src (common/icon-path "Info-Info")}]
              [:span
               "These settings are only for 1.0 builds. To define parallelism for 2.0 jobs, "
               [:a
                {:href "/docs/2.0/configuration-reference/#jobs"}
                "read our documentation"]
               "."]]]
            [:legend (str "Change parallelism for " (vcs-url/project-name (get-in project-data [:project :vcs_url])))]
            (if-not (:plan project-data)
              (spinner)
              (list (parallelism-picker project-data owner)
                    (project-common/mini-parallelism-faq project-data)))]])))))

(defn result-box
  [{:keys [success? message result-path]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.flash-error-wrapper.row-fluid
         [:div.offset1.span10
          [:div.alert.alert-block {:class (if success?
                                            "alert-success"
                                            "alert-danger")}
           [:a.close {:on-click #(raise! owner [:dismiss-result result-path])}
            "x"]
           message]]]))))

(defn clear-cache-button
  [cache-type project-data owner]
  (button/managed-button
    {:loading-text "Clearing cache..."
     :success-text "Cleared"
     :failure-text "Clearing failed"
     :on-click #(raise! owner
                        [:clear-cache
                         {:type cache-type
                          :project-id (-> project-data
                                          :project
                                          project-model/id)}])
     :kind :primary}
    "Clear"))

(defn clear-caches
  [project-data owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:section
         [:article
          [:legend "Clear Caches"]
          [:div.card.detailed
           [:h4 "Dependency Cache"]
           [:div.details
            (when-let [res (-> project-data :build-cache-clear)]
              (om/build result-box
                        (assoc res
                               :result-path
                               (conj state/project-data-path :build-cache-clear))))
            [:p "CircleCI saves a copy of your dependencies to prevent downloading them all on each build."]
            [:p [:b "NOTE: "] "Clearing your dependency cache will cause the next build to completely recreate dependencies. In some cases this can add considerable time to that build.  Also, you may need to ensure that you have no running builds when using this to prevent the old cache from being resaved."]
            [:hr]
            (clear-cache-button "build" project-data owner)]]
          [:div.card.detailed
           [:h4 "Source Cache"]
           [:div.details
            (when-let [res (-> project-data :source-cache-clear)]
              (om/build result-box
                        (assoc res
                               :result-path
                               (conj state/project-data-path :source-cache-clear))))
            [:p "CircleCI saves a copy of your source code on our system and pulls only changes since the last build on each branch."]
            [:p [:b "NOTE: "] "Clearing your source cache will cause the next build to download a fresh copy of your source. In some cases this can add considerable time to that build.  Doing this too frequently or when you have a large number of high parallelism builds also carries some risk of becoming rate-limited by GitHub, which will prevent your builds from being able to download source until the rate-limit expires.  Also, you may need to ensure that you have no running builds when using this to prevent the old cache from being resaved."]
            [:hr]
            (clear-cache-button "source" project-data owner)]]]]))))

(def env-var-tutorial
  [:p
   " To disable string substitution you need to escape the " [:code "$"]
   " characters by prefixing them with " [:code "\\"] "."
   " For example, a value like " [:code "usd$"] " would be entered as " [:code "usd\\$"] "."])

(defn- add-env-vars-modal
  [{:keys [close-fn project-id project]} owner]
  (let [inputs (inputs/get-inputs-from-app-state owner)
        new-env-var-name (:new-env-var-name inputs)
        new-env-var-value (:new-env-var-value inputs)
        track-modal-dismissed (fn [{:keys [component]}]
                                ((om/get-shared owner :track-event) {:event-type :add-env-vars-modal-dismissed
                                                                     :properties {:component component
                                                                                  :project-id project-id}}))
        track-env-vars-added (fn []
                                ((om/get-shared owner :track-event) {:event-type :env-vars-added
                                                                     :properties {:project-id project-id}}))
        success-fn (fn [] (do (track-env-vars-added)
                              (close-fn)))]
    (reify
      om/IDidMount
      (did-mount [_]
        ((om/get-shared owner :track-event) {:event-type :add-env-vars-modal-impression
                                             :properties {:project-id project-id}}))
      om/IRender
      (render [_]
        (modal/modal-dialog {:title "Add an Environment Variable"
                             :body (html
                                     [:div
                                      env-var-tutorial
                                      (form/form {}
                                        (om/build form/text-field {:label "Name"
                                                                   :required true
                                                                   :value new-env-var-name
                                                                   :auto-focus true
                                                                   :on-change #(utils/edit-input owner (conj state/inputs-path :new-env-var-name) %)})
                                        (om/build form/text-field {:label "Value"
                                                                   :required true
                                                                   :value new-env-var-value
                                                                   :auto-complete "off"
                                                                   :on-change #(utils/edit-input owner (conj state/inputs-path :new-env-var-value) %)}))])
                             :actions [(button/button {:on-click #(do (track-modal-dismissed {:component "cancel"})
                                                                      (close-fn))
                                                       :kind :flat}
                                                      "Cancel")
                                       (button/managed-button {:failed-text "Failed"
                                                               :success-text "Added"
                                                               :loading-text "Adding..."
                                                               :kind :primary
                                                               :on-click #(raise! owner [:created-env-var
                                                                                         {:project-id project-id
                                                                                          :on-success success-fn}])}
                                         "Add Variable")]
                             :close-fn #(do (track-modal-dismissed {:component "close-x"})
                                            (close-fn))})))))

(defn- import-env-vars-modal
  [{:keys [close-fn project-id project projects]} owner]
  (let [set-selected-project (fn [vcs-url]
                               (om/set-state! owner {:selected-project {:vcs-url vcs-url :env-vars nil}})
                               (-> {:method :get
                                    :uri (gstring/format "/api/v1.1/project/%s/%s/envvar"
                                                         (vcs-url/vcs-type vcs-url)
                                                         (vcs-url/project-name vcs-url))
                                    :handler (fn [{:keys [resp]}]
                                               (om/set-state!
                                                 owner
                                                 {:selected-project {:vcs-url vcs-url
                                                                     :env-vars (->> resp
                                                                                    (map (fn [{:keys [name] :as env-var}]
                                                                                           [name (assoc env-var
                                                                                                        :checked
                                                                                                        false)]))
                                                                                    (into (sorted-map)))}}))
                                    :error-handler (fn [{:keys [resp]}]
                                                     (om/set-state!
                                                       owner
                                                       {:selected-project {:vcs-url vcs-url
                                                                           :env-vars []}})
                                                     ((om/get-shared owner :track-event) {:event-type :env-vars-fetch-failed
                                                                                          :properties {:project-id project-id}}))}
                                   ajax/ajax-opts
                                   clj-ajax/ajax-request))
        track-modal-dismissed (fn [{:keys [component]}]
                                ((om/get-shared owner :track-event) {:event-type :import-env-vars-modal-dismissed
                                                                     :properties {:component component
                                                                                  :project-id project-id}}))
        track-env-vars-imported (fn []
                                  ((om/get-shared owner :track-event) {:event-type :env-vars-imported
                                                                       :properties {:project-id project-id}}))
        success-fn (fn [] (do (track-env-vars-imported)
                              (close-fn)))
        vcs-url (:vcs_url project)
        org-name (vcs-url/org-name vcs-url)]
    (reify
      om/IDidMount
      (did-mount [_]
        (let [vcs-url (->> projects
                           first
                           :vcs_url)]
          (set-selected-project vcs-url)
          ((om/get-shared owner :track-event) {:event-type :import-env-vars-modal-impression
                                               :properties {:project-id project-id}})))
      om/IRenderState
      (render-state [_ {:keys [selected-project] :as state}]
        (let [env-vars-map (:env-vars selected-project)
              env-vars (vals env-vars-map)
              selected-vcs-url (:vcs-url selected-project)
              selected-repo-name (vcs-url/repo-name selected-vcs-url)
              handle-checkbox-click-fn (fn [item]
                                         #(om/set-state!
                                            owner
                                            (assoc-in state
                                                      [:selected-project :env-vars (:name item) :checked]
                                                      (not (:checked item)))))
              selected-env-vars (->> env-vars
                                     (filter :checked)
                                     (map :name))
              any-deselected? (not= (count selected-env-vars)
                                    (count env-vars))
              handle-bulk-select-fn (fn [any-deselected?]
                                      #(om/set-state!
                                         owner
                                         (assoc-in state
                                                   [:selected-project :env-vars]
                                                   (->> env-vars-map
                                                        (map (fn [[key value]]
                                                               [key (assoc value :checked any-deselected?)]))
                                                        (into (sorted-map))))))
              modal-import-on-click #(raise! owner
                                             [:import-env-vars {:src-project-vcs-url selected-vcs-url
                                                                :dest-project-vcs-url vcs-url
                                                                :env-vars selected-env-vars
                                                                :on-success success-fn}])]
          (modal/modal-dialog
            {:title "Import an Environment Variable"
             :body
             (component
              (html
               [:.import-envvars
                [:p
                 (gstring/format "You can import environment variables from any project that belong to %s."
                                 org-name)]
                [:div
                 (if-not selected-project
                   (spinner)
                   (form/form
                     {}
                     (dropdown/dropdown {:options (map (fn [proj]
                                                         (let [proj-vcs-url (:vcs_url proj)]
                                                           [proj-vcs-url (vcs-url/repo-name proj-vcs-url)]))
                                                       projects)
                                         :on-change set-selected-project
                                         :name "project"
                                         :value selected-vcs-url})
                     (cond
                       (nil? env-vars-map)
                       (spinner)

                       (empty? env-vars-map)
                       (html [:span
                              "This project has no environment variables. This may be because your user permissions. Check the "
                              [:a {:href (routes/v1-project-settings-path {:org org-name
                                                                           :repo selected-repo-name})
                                   :on-click #((om/get-shared owner :track-event) {:event-type :project-settings-clicked
                                                                                   :properties {:org org-name
                                                                                                :repo selected-repo-name
                                                                                                :project-vcs-url selected-vcs-url}})}
                               "project's settings"]
                              " and/or reach out to a VCS provider admin withing your organization."])

                       env-vars-map
                       (html
                        [:.env-vars-table-container
                         (om/build
                          table/table
                          {:rows env-vars
                           :key-fn :name
                           :columns [{:header "Name"
                                      :cell-fn :name}
                                     {:header "Value"
                                      :cell-fn :value}
                                     {:header [:a
                                               {:href "javaScript:void(0)" :on-click (handle-bulk-select-fn any-deselected?)}
                                               (if any-deselected?
                                                 "Select all"
                                                 "Deselect all")]
                                      :type #{:shrink :right}
                                      :cell-fn
                                      (fn [item]
                                        (html
                                          [:.checkbox
                                           [:label
                                            [:input {:type "checkbox"
                                                     :checked (:checked item)
                                                     :name "select to import checkbox"
                                                     :on-click (handle-checkbox-click-fn item)}]]]))}]})]))))]]))
             :actions [(button/button {:on-click #(do (track-modal-dismissed {:component "cancel"})
                                                      (close-fn))
                                       :kind :flat}
                                      "Cancel")
                       (button/managed-button {:failed-text "Permission Denied"
                                               :success-text "Imported"
                                               :loading-text "Importing..."
                                               :kind :primary
                                               :disabled? (= (count selected-env-vars) 0)
                                               :on-click modal-import-on-click}
                                              "Import")]
             :close-fn #(do (track-modal-dismissed {:component "close-x"})
                            (close-fn))}))))))

(defn env-vars [{:keys [project-data projects org-admin?]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal nil})
    om/IDidMount
    (did-mount [_]
      (let [project (:project project-data)]
        (api/get-org-settings-normalized (project-model/org-name project)
                                         (project-model/vcs-type project)
                                         (om/get-shared owner [:comms :api]))))
    om/IRenderState
    (render-state [_ {:keys [show-modal]}]
      (let [project (:project project-data)
            project-id (project-model/id project)
            close-fn #(om/set-state! owner :show-modal nil)
            other-projects (->> projects
                                (filter #(not= (project-model/id %) project-id))
                                (sort-by (comp string/lower-case project-model/project-name)))
            disabled-import-env-vars? (or (empty? other-projects)
                                          (not org-admin?))]
        (html
         ;; The :section and :article here are artifacts of the legacy styling
         ;; of the settings pages, and should go away as the structure of the
         ;; settings pages is addressed.
         [:section.envvars
          [:article
           [:legend "Environment Variables"]
           (card/titled
             {:title (str "Environment Variables for " (vcs-url/project-name (:vcs_url project)))
              :action [(when (feature/enabled? :import-env-vars)
                         (button/button {:on-click #(om/set-state! owner :show-modal :import-env-vars)
                                         :kind :primary
                                         :size :small
                                         :label (when (not org-admin?)
                                                  "To use this function, you must be an organization admin on your VCS-provider")
                                         :disabled? disabled-import-env-vars?}
                                        "Import Variable(s)"))
                       (button/button {:on-click #(om/set-state! owner :show-modal :add-env-var)
                                       :kind :primary
                                       :size :small}
                                      "Add Variable")]}
            (html
             [:div
              [:p
               "Add environment variables to the build. You can add sensitive data (e.g. API keys) here, rather than placing them in the repository. "
               "The values can be any bash expression and can reference other variables, such as setting "
               [:code "M2_MAVEN"] " to " [:code "${HOME}/.m2)"] "."]

              (case show-modal
                :import-env-vars (om/build import-env-vars-modal
                                           {:close-fn close-fn
                                            :project-id project-id
                                            :project project
                                            :projects other-projects})
                :add-env-var (om/build add-env-vars-modal {:close-fn close-fn
                                                           :project-id project-id
                                                           :project project})
                nil)
              (when-let [env-vars-entries (->> (:envvars project-data)
                                               (sort-by key)
                                               seq)]
                (om/build table/table
                          {:rows env-vars-entries
                           :key-fn key
                           :columns [{:header "Name"
                                      :cell-fn key}
                                     {:header "Value"
                                      :cell-fn val}
                                     {:header "Remove"
                                      :type #{:shrink :right}
                                      :cell-fn
                                      (fn [env-var]
                                        (om/build remove-action-button
                                                  {:confirmation-question
                                                   (str
                                                    "Are you sure you want to remove the environment variable \""
                                                    (key env-var)
                                                    "\"?")

                                                   :remove-fn
                                                   #(raise! owner [:deleted-env-var {:project-id project-id
                                                                                     :env-var-name (key env-var)}])}))}]}))]))]])))))

(defn advance [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))
            ;; This project's feature flags
            feature-flags (project-model/feature-flags project)
            describe-flag (fn [{:keys [flag title blurb]}]
                            (when (contains? (set (keys feature-flags)) flag)
                              [:li
                               [:h4 title]
                               [:p blurb]
                               [:form
                                [:ul
                                 [:li.radio
                                  [:label
                                   [:input
                                    {:type "radio"
                                     :checked (get feature-flags flag)
                                     :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                               :flag flag
                                                                                               :value true}])}]
                                   " On"]]
                                 [:li.radio
                                  [:label
                                   [:input
                                    {:type "radio"
                                     :checked (not (get feature-flags flag))
                                     :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                               :flag flag
                                                                                               :value false}])}]
                                   " Off"]]]]]))]
        (html
         [:section
          [:article
           [:legend "Advanced Settings"]
           [:ul

            (let [pretty-vcs (utils/prettify-vcs_type (:vcs-type project))]
              (describe-flag {:flag :set-github-status
                              :title (gstring/format "%s Status updates" pretty-vcs)
                              :blurb [:p
                                      "By default, we update the status of every pushed commit with "
                                      (gstring/format "%s's status API. If you'd like to turn this off (if, for example, "
                                                      pretty-vcs)
                                      "this is conflicting with another service), you can do so below."]}))
            (describe-flag {:flag :oss
                            :title "Free and Open Source"
                            :blurb [:p
                                    "Organizations have three free containers "
                                    "reserved for F/OSS projects; enabling this will allow this project's "
                                    "builds to use them and let others see your builds, both through the "
                                    "web UI and the API."]})
            (describe-flag {:flag :build-fork-prs
                            :title "Build forked pull requests"
                            :blurb (list
                                    [:p
                                     "Run builds for pull requests from forks. "
                                     "CircleCI will automatically update the commit status shown on GitHub's "
                                     "pull request page."])})
            (describe-flag {:flag :forks-receive-secret-env-vars
                            :title "Pass secrets to builds from forked pull requests"
                            :blurb (list
                                    [:p
                                     "Run builds for fork pull request changes with this project's configuration, environment variables, and secrets. "]
                                    [:p
                                     "There are serious security concerns with this setting (see "
                                     [:a {:href "https://circleci.com/docs/fork-pr-builds/"} "the documentation"] " for details.) "
                                     "If you have SSH keys, sensitive env vars or AWS credentials stored in your project settings and "
                                     "untrusted forks can make pull requests against your repo, then this option "
                                     "isn't for you!"])})
            (describe-flag {:flag :build-prs-only
                            :title "Only build pull requests"
                            :blurb [:p
                                    "By default, we will build all the commits for this project. Once turned on, we will only build branches "
                                    "that have associated pull requests open. Note: For your default branch, we will always build all commits."]})
            (describe-flag {:flag :autocancel-builds
                            :title "Auto-cancel redundant builds"
                            :blurb [:p
                                    "With the exception of your default branch, we will automatically cancel any queued or running builds on "
                                    "a branch when a newer build is triggered on that same branch. This feature will only apply to builds "
                                    "triggered by pushes to GitHub."]})
            (describe-flag {:flag :osx-code-signing-enabled
                            :title "Code Signing Support"
                            :blurb [:p
                                    "Enable automatic importing of code-signing identities and provisioning "
                                    "profiles into the system keychain to simplify the code-signing process."]})
            (describe-flag {:flag :auto-transition
                            :title "Automated transition to 2.0 platform"
                            :blurb [:p
                                    "This enables running builds on our 2.0 platform even if they are only configured for the 1.0 platform. "
                                    "It is available and turned on by default for projects we expect it to work for. "
                                    "You can turn it off if it is not working well for you. "]})]]])))))

(defn dependencies [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            inputs (inputs/get-inputs-from-app-state owner)
            settings (state-utils/merge-inputs project inputs [:setup :dependencies :post_dependencies])]
        (html
         [:section.dependencies-page
          [:article
           [:legend "Install dependencies for " (vcs-url/project-name (:vcs_url project))]
           [:p
            "You can also set your dependencies commands from your "
            [:a {:href "https://circleci.com/docs/configuration/#dependencies"} "circle.yml"] ". "
            "Note that anyone who can see this project on GitHub will be able to see these in your build pages. "
            "Don't put any secrets here that you wouldn't check in! Use our "
            [:a {:href "#env-vars"} "environment variables settings page"]
            " instead."]
           [:div.dependencies-inner
            [:form.spec_form
             [:fieldset
              [:div.form-group
               [:label "Pre-dependency commands"]
               [:textarea.dumb.form-control {:name "setup",
                                             :required true
                                             :auto-focus true
                                             :value (str (:setup settings))
                                             :on-change #(utils/edit-input owner (conj state/inputs-path :setup) % owner)}]
               [:p "Run extra commands before the normal setup, these run before our inferred commands. All commands are arbitrary bash statements, and run on Ubuntu 12.04. Use this to install and setup unusual services, such as specific DNS provisions, connections to a private services, etc."]]
              [:div.form-group
               [:label "Dependency overrides"]
               [:textarea.dumb.form-control {:name "dependencies",
                                             :required true
                                             :value (str (:dependencies settings))
                                             :on-change #(utils/edit-input owner (conj state/inputs-path :dependencies) %)}]
               [:p "Replace our inferred setup commands with your own bash commands. Dependency overrides run instead of our inferred commands for dependency installation. If our inferred commands are not to your liking, replace them here. Use this to override the specific pre-test commands we run, such as "
                [:code "bundle install"] ", " [:code "rvm use"] ", " [:code "ant build"] ", "
                [:code "configure"] ", " [:code "make"] ", etc."]]
              [:div.form-group
               [:label "Post-dependency commands"]
               [:textarea.dumb.form-control {:required true
                                             :value (str (:post_dependencies settings))
                                             :on-change #(utils/edit-input owner (conj state/inputs-path :post_dependencies) %)}]
               [:p "Run extra commands after the normal setup, these run after our inferred commands for dependency installation. Use this to run commands that rely on the installed dependencies."]]
              (button/managed-button
               {:loading-text "Saving..."
                :on-click #(raise! owner [:saved-dependencies-commands {:project-id project-id}])
                :kind :primary}
               "Next, set up your tests")]]]]])))))

(defn tests [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            inputs (inputs/get-inputs-from-app-state owner)
            settings (state-utils/merge-inputs project inputs [:test :extra])]
        (html
         [:section.tests-page
          [:article
           [:legend "Set up tests for " (vcs-url/project-name (:vcs_url project))]
           [:p
            "You can also set your test commands from your "
            [:a {:href "https://circleci.com/docs/configuration/#dependencies"} "circle.yml"] ". "
            "Note that anyone who can see this project on GitHub will be able to see these in your build pages. "
            "Don't put any secrets here that you wouldn't check in! Use our "
            [:a {:href "#env-vars"} "environment variables settings page"]
            " instead."]
           [:div.tests-inner
            [:fieldset.spec_form
             [:div.form-group
              [:label "Test commands"]
              [:textarea.dumb.form-control {:name "test",
                                            :required true
                                            :auto-focus true
                                            :value (str (:test settings))
                                            :on-change #(utils/edit-input owner (conj state/inputs-path :test) %)}]
              [:p "Replace our inferred test commands with your own inferred commands. These test commands run instead of our inferred test commands. If our inferred commands are not to your liking, replace them here. As usual, all commands are arbitrary bash, and run on Ubuntu 12.04."]]
             [:div.form-group
              [:label "Post-test commands"]
              [:textarea.dumb.form-control {:name "extra",
                                            :required true
                                            :value (str (:extra settings))
                                            :on-change #(utils/edit-input owner (conj state/inputs-path :extra) %)}]
              [:p "Run extra test commands after the others finish. Extra test commands run after our inferred commands. Add extra tests that we haven't thought of yet."]]
             (button/managed-button
              {:loading-text "Saving..."
               :success-text "Saved"
               :on-click #(raise! owner [:saved-test-commands {:project-id project-id}])
               :kind :primary}
              "Save Commands")
             [:div.try-out-build
              (om/build branch-picker
                        project-data
                        {:opts {:button-text "Save & Go!"
                                :channel-message :saved-test-commands
                                :channel-args {:project-id project-id :start-build? true}}})]]]]])))))

(defn fixed-failed-input [{:keys [settings field]} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/tooltip (str "#fixed-failed-input-tooltip-hack-" (string/replace (name field) "_" "-"))))
    om/IRender
    (render [_]
      (html
       (let [notify_pref (get settings field)
             id (string/replace (name field) "_" "-")]
         [:label {:for id}
          [:input {:id id
                   :checked (= "smart" notify_pref)
                   ;; note: can't use inputs-state here because react won't let us
                   ;;       change checked state without rerendering
                   :on-change #(utils/edit-input owner (conj state/project-path field) %
                                                 :value (if (= "smart" notify_pref) nil "smart"))
                   :value "smart"
                   :type "checkbox"}]
          [:span "Fixed/Failed Only"]
          [:i.fa.fa-question-circle {:id (str "fixed-failed-input-tooltip-hack-" id)
                                     :title "Only send notifications for builds that fail or fix the tests and the first build on a new branch. Otherwise, send a notification for every build."}]])))))

(defn chatroom-item [project-id settings owner
                     {:keys [service service-id doc properties inputs show-fixed-failed? top-section-content settings-keys]
                      :or {service-id (string/lower-case service)}}]
    [:div {:class (str "chat-room-item " service-id)}
     [:div.chat-room-head [:h4 {:class (str "chat-i-" service-id)} service]]
     [:div.chat-room-body
      [:section
       doc
       top-section-content
       (when show-fixed-failed?
         (om/build fixed-failed-input {:settings settings :field (keyword (str service-id "_notify_prefs"))}))]
      [:section
       (for [{:keys [field placeholder read-only] :as input} inputs
             :when input]
         (list
          [:input {:id (string/replace (name field) "_" "-") :required true :type "text"
                   :value (str (get settings field))
                   :read-only read-only
                   :on-change #(utils/edit-input owner (conj state/inputs-path field) %)}]
          [:label {:placeholder placeholder}]))
       (let [event-data {:project-id project-id :merge-paths (map vector settings-keys)}]
         [:div.chat-room-buttons
          (forms/managed-button
           [:button.save {:on-click #(raise! owner [:saved-project-settings event-data])
                          :data-loading-text "Saving"
                          :data-success-text "Saved"}
            "Save"])
          (forms/managed-button
           [:button.test {:on-click #(raise! owner [:test-hook (assoc event-data :service service-id)])
                          :data-loading-text "Testing"
                          :data-success-text "Tested"}
            "& Test Hook"])])]]])

(defn webhooks [project-data owner]
  (om/component
   (html
    [:section
     [:article
      [:legend "Webhooks"]
      [:div.doc
       [:p
        "The CircleCI 1.0 platform also supports webhooks, which run at the end of a build. They can be configured in your "
        [:a {:href "https://circleci.com/docs/configuration#notify" :target "_blank"}
         "circle.yml file"]
        "."]]]])))

(defn- legacy-slack-notifications-helper [{:keys [on-change show-slack-channel-override show-legacy?]}]
  {:service "Slack"
   :doc (list (when show-legacy?
                [:p "You're using a deprecated version of our slack integration. We encourage you to set up Slack using Webhook URL instead."])
              [:p "To get your Webhook URL, visit Slack's "
               [:a {:href "https://my.slack.com/services/new/circleci"}
                "CircleCI Integration"]
               " page, choose a default channel, and click the green \"Add CircleCI Integration\" button at the bottom of the page."]
              [:div
               [:label
                [:input
                 {:type "checkbox"
                  :checked show-slack-channel-override
                  :on-change on-change}]
                [:span "Override room"]
                [:i.fa.fa-question-circle {:id "slack-channel-override"
                                           :title "If you want to send notifications to a different channel than the webhook URL was created for, enter the channel ID or channel name below."}]]])
   :inputs (if show-legacy?
             [{:field :slack_subdomain :placeholder "Subdomain"}
              {:field :slack_channel :placeholder "Channel"}
              {:field :slack_api_token :placeholder "API"}]

             [{:field :slack_webhook_url :placeholder "Webhook URL"}
              (when show-slack-channel-override
                {:field :slack_channel_override :placeholder "Room"})])
   :show-fixed-failed? true
   :settings-keys project-model/slack-keys})

(defn- slack-integration-notifications-helper [{:keys [vcs-url]}]
  ; TODO: may want to move this into a separate page pending design
  {:service "Slack Integration"
   :service-id "slack-integration"
   :doc (html
         [:.slack-notifications-settings
          [:p "To configure Slack notifications, please click the \"Add to Slack\" button below and select a team and channel."]
          [:.add-to-slack
           [:a {:href (slack/add-to-slack-url vcs-url)}
            [:img {:height 40
                   :width 139
                   :src "https://platform.slack-edge.com/img/add_to_slack.png"
                   :src-set "https://platform.slack-edge.com/img/add_to_slack.png 1x, https://platform.slack-edge.com/img/add_to_slack@2x.png 2x"}]]]])
   :inputs [{:field :slack_integration_team :placeholder "Team" :read-only true}
            {:field :slack_integration_channel :placeholder "Channel" :read-only true}]
   :show-fixed-failed? true
   :settings-keys project-model/slack-integration-keys})

(defn notifications [project-data owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-slack-channel-override (-> project-data :project :slack_channel_override seq nil? not)})
    om/IDidMount
    (did-mount [_]
      (utils/equalize-size (om/get-node owner) "chat-room-item")
      (utils/tooltip "#slack-channel-override"))
    om/IDidUpdate
    (did-update [_ _ _]
      (utils/equalize-size (om/get-node owner) "chat-room-item"))
    om/IRenderState
    (render-state [_ state]
      (let [project (:project project-data)
            project-id (project-model/id project)
            inputs (inputs/get-inputs-from-app-state owner)
            settings (state-utils/merge-inputs project inputs project-model/notification-keys)]
        (html
         [:section
          [:article
           [:legend "Chatroom Integrations"]
           [:p "If you want to control chat notifications on a per branch basis, "
            [:a {:href "https://circleci.com/docs/configuration#per-branch-notifications"} "see our documentation"] "."]
           [:div.chat-rooms
            (let [chat-spec [(when (feature/enabled? :sign-in-with-slack)
                               (slack-integration-notifications-helper {:vcs-url project-id}))

                             (legacy-slack-notifications-helper
                               {:on-change #(do (om/update-state! owner :show-slack-channel-override not)
                                                (utils/edit-input owner (conj state/project-path :slack_channel_override) % :value ""))
                                :show-slack-channel-override (:show-slack-channel-override state)
                                ; only show legacy if slack_webhook_url isn't configured
                                ; and there is existing legacy slack hook information.
                                :show-legacy? (and (not (seq (:slack_webhook_url project)))
                                                   (or (seq (:slack_subdomain project))
                                                       (seq (:slack_api_token project))
                                                       (seq (:slack_channel project))))})

                             {:service "Hipchat"
                              :doc (list [:p "To get your API token, create a \"notification\" token via the "
                                          [:a {:href "https://hipchat.com/admin/api"} "HipChat site"] "."]
                                         [:div
                                          [:label ;; hipchat is a special flower
                                           {:for "hipchat-notify"}
                                           [:input#hipchat-notify
                                            {:type "checkbox"
                                             :checked (:hipchat_notify settings)
                                             ;; n.b. can't use inputs-state b/c react won't changed
                                             ;;      checked state without a rerender
                                             :on-change #(utils/edit-input owner (conj state/project-path :hipchat_notify) %
                                                                           :value (not (:hipchat_notify settings)))}]
                                           [:span "Show popups"]]])
                              :inputs [{:field :hipchat_room :placeholder "Room"}
                                       {:field :hipchat_api_token :placeholder "API"}]
                              :show-fixed-failed? true
                              :settings-keys project-model/hipchat-keys}

                             {:service "Flowdock"
                              :doc [:p "To get your API token, visit your Flowdock, then click the \"Settings\" icon on the left. On the settings tab, click \"Team Inbox\""]
                              :inputs [{:field :flowdock_api_token :placeholder "API"}]
                              :show-fixed-failed? false
                              :settings-keys project-model/flowdock-keys}

                             {:service "Campfire"
                              :doc [:p "To get your API token, visit your company Campfire, then click \"My info\". Note that if you use your personal API token, campfire won't show the notifications to you!"]
                              :inputs [{:field :campfire_room :placeholder "Room"}
                                       {:field :campfire_subdomain :placeholder "Subdomain"}
                                       {:field :campfire_token :placeholder "API"}]
                              :show-fixed-failed? true
                              :settings-keys project-model/campfire-keys}

                             {:service "IRC"
                              :doc nil
                              :inputs [{:field :irc_server :placeholder "Hostname"}
                                       {:field :irc_channel :placeholder "Channel"}
                                       {:field :irc_keyword :placeholder "Private Keyword"}
                                       {:field :irc_username :placeholder "Username"}
                                       {:field :irc_password :placeholder "Password (optional)"}]
                              :show-fixed-failed? true
                              :settings-keys project-model/irc-keys}]]
              (for [enabled-chat-spec (remove nil? chat-spec)]
                (chatroom-item project-id settings owner enabled-chat-spec)))]]])))))

(def status-styles
  {"badge" {:label "Badge" :string ".png?style=badge"}
   "shield" {:label "Shield" :string ".svg?style=shield"}
   "svg" {:label "Badge" :string ".svg?style=svg"}})

(def status-formats
  {"image" {:label "Image URL"
            :template :image}
   "markdown" {:label "Markdown"
               :template #(str "[![CircleCI](" (:image %) ")](" (:target %) ")")}
   "textile" {:label "Textile"
              :template #(str "!" (:image %) "!:" (:target %))}
   "rdoc" {:label "Rdoc"
           :template #(str "{<img src=\"" (:image %) "\" alt=\"CircleCI\" />}[" (:target %) "]")}
   "asciidoc" {:label "AsciiDoc"
               :template #(str "image:" (:image %) "[\"CircleCI\", link=\"" (:target %) "\"]")}
   "rst" {:label "reStructuredText"
          :template #(str ".. image:: " (:image %) "\n    :target: " (:target %))}
   "pod" {:label "pod"
          :template #(str "=for HTML <a href=\"" (:target %) "\"><img src=\"" (:image %) "\"></a>")}})

(defn status-badges [project-data owner]
  (let [project (:project project-data)
        oss (project-model/feature-enabled? project :oss)
        ;; Get branch selection or the empty string for the default branch.
        branches (branch-names project-data)
        branch (get-in project-data [:status-badges :branch])
        branch (or (some #{branch} branches) "")
        ;; Get token selection, or the empty string for no token. Tokens must have "status" scope.
        ;; OSS projects default to no token, private projects default to the first available.
        ;; If a token is required, but unavailable, no token is selected and the UI shows a warning.
        tokens (filter #(= (:scope %) "status") (:tokens project-data))
        token (get-in project-data [:status-badges :token])
        token (some #{token} (cons "" (map :token tokens)))
        token (str (if (or oss (some? token)) token (-> tokens first :token)))
        ;; Generate the status badge with current settings.
        project-name (vcs-url/project-name (:vcs_url project))
        vcs-path (if (seq branch) (str project-name "/tree/" (gstring/urlEncode branch)) project-name)
        short-vcs-type (vcs/->short-vcs (vcs-url/vcs-type (:vcs_url project)))
        target (str (.. js/window -location -origin) "/" short-vcs-type "/" vcs-path)
        style (get-in project-data [:status-badges :style] "svg")
        image (str target (get-in status-styles [style :string]))
        image (if (seq token) (str image "&circle-token=" token) image)
        format (get-in project-data [:status-badges :format] "markdown")
        code ((:template (status-formats format)) {:image image :target target})]
    (om/component
     (html
      [:section.status-page
       [:article
        [:legend "Status badges for " project-name]
        [:div "Use this tool to easily create embeddable status badges. Perfect for your project's README or wiki!"]
        [:div.status-page-inner
         [:form
          [:div.branch
           [:h4 "Branch"]
           [:select.form-control {:value branch
                                  :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :branch) %)}
            [:option {:value ""} "Default"]
            [:option {:disabled "disabled"} "-----"]
            (for [branch branches]
              [:option {:value branch} branch])]]

          [:div.token
           [:h4 "API Token"]
           (when-not (or oss (seq token))
             [:p [:span.warning "Warning: "] "Private projects require an API token - " [:a {:href "#api"} "add one with status scope"] "."])
           [:select.form-control {:value token
                                  :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :token) %)}
            [:option {:value ""} "None"]
            [:option {:disabled "disabled"} "-----"]
            (for [{:keys [token label]} tokens]
              [:option {:value token} label])]]

          ;; Hide style selector until "badge" style is improved. See PR #3140 discussion.
          #_[:div.style
             [:h4 "Style"]
             [:fieldset
              (for [[id {:keys [label]}] status-styles]
                [:label.radio
                 [:input {:name "branch" :type "radio" :value id :checked (= style id)
                          :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :style) %)}]
                 label])]]

          [:div.preview
           [:h4 "Preview"]
           [:img {:src image}]]

          [:div.embed
           [:h4 "Embed Code"]
           [:select.form-control {:value format
                                  :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :format) %)}
            (for [[id {:keys [label]}] status-formats]
              [:option {:value id} label])]
           [:textarea.dumb.form-control {:readonly true
                                         :value code
                                         :on-click #(.select (.-target %))}]]]]]]))))

(defn ssh-keys [project-data owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})
    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [project (:project project-data)
            project-id (project-model/id project)
            {:keys [hostname private-key]
             :or {hostname "" private-key ""}} (:new-ssh-key project-data)]
        (html
         [:section
          [:article
           [:legend "SSH Permissions"]
           (card/titled
            {:title (str "SSH keys for " (vcs-url/project-name (:vcs_url project)))
             :action (button/button {:on-click #(om/set-state! owner :show-modal? true)
                                     :kind :primary
                                     :size :small}
                                    "Add SSH Key")}
            (html
             [:div
              [:p "Add keys to the build VMs that you need to deploy to your machines. If the hostname field is blank, the key will be used for all hosts."]
              (when show-modal?
                (let [close-fn #(om/set-state! owner :show-modal? false)]
                  (modal/modal-dialog
                   {:title "Add an SSH Key"
                    :body
                    (form/form {}
                               (om/build form/text-field {:label "Hostname"
                                                          :value hostname
                                                          :on-change #(utils/edit-input owner (conj state/project-data-path :new-ssh-key :hostname) %)})
                               (om/build form/text-area {:label "Private Key"
                                                         :required true
                                                         :value private-key
                                                         :on-change #(utils/edit-input owner (conj state/project-data-path :new-ssh-key :private-key) %)}))
                    :actions [(button/button {:on-click close-fn
                                              :kind :flat}
                                             "Cancel")
                              (button/managed-button
                               {:failed-text "Failed"
                                :success-text "Saved"
                                :loading-text "Saving..."
                                :on-click #(raise! owner [:saved-ssh-key {:project-id project-id
                                                                          :ssh-key {:hostname hostname
                                                                                    :private_key private-key}
                                                                          :on-success close-fn}])
                                :kind :primary}
                               "Add SSH Key")]
                    :close-fn close-fn})))
              (when-let [ssh-keys (seq (:ssh_keys project))]
                (let [remove-key-button
                      (fn [ssh-key]
                        (om/build remove-action-button
                                  {:confirmation-question
                                   (str "Are you sure you want to remove the SSH key \"" (:fingerprint ssh-key) "\""
                                        (if (:hostname ssh-key)
                                          (str " for the hostname \"" (:hostname ssh-key) "\"")
                                          " for all hosts")
                                        "?")

                                   :remove-fn
                                   #(raise! owner [:deleted-ssh-key (-> ssh-key
                                                                        (select-keys [:hostname :fingerprint])
                                                                        (assoc :project-id project-id))])}))]
                  (om/build table/table
                            {:rows ssh-keys
                             :key-fn (comp hash (juxt :hostname :fingerprint))
                             :columns [{:header "Hostname"
                                        :cell-fn :hostname}
                                       {:header "Fingerprint"
                                        :cell-fn :fingerprint}
                                       {:header "Remove"
                                        :type #{:shrink :right}
                                        :cell-fn remove-key-button}]})))]))]])))))

(defn checkout-key-link [key project user]
  (cond (= "deploy-key" (:type key))
        (case (:vcs-type project)
          "bitbucket" (str (bb-utils/http-endpoint) "/" (vcs-url/project-name (:vcs_url project)) "/admin/deploy-keys/")
          "github" (str (gh-utils/http-endpoint) "/" (vcs-url/project-name (:vcs_url project)) "/settings/keys"))
        (and (= "github-user-key" (:type key)) (= (:login key) (:login user)))
        (str (gh-utils/http-endpoint) "/settings/ssh")
        (and (= "bitbucket-user-key" (:type key))
             (= (:login key) (-> user :bitbucket :login)))
        (str (bb-utils/http-endpoint) "/account/user/" (:login key)
             "/ssh-keys/")
        :else nil))

(defn checkout-key-description [key project]
  (case (:type key)
    "deploy-key" (str (vcs-url/project-name (:vcs_url project)) " deploy key")
    "github-user-key" (str (:login key) " user key")
    "bitbucket-user-key" (str (:login key) " user key")
    nil))

(defmulti add-user-key-section (fn [data owner] (-> data :project-data :project :vcs-type keyword)))

(defmethod add-user-key-section :github [data owner]
  (reify
    om/IRender
    (render [_]
      (let [user (:user data)
            project-data (:project-data data)
            project (:project project-data)
            checkout-keys (:checkout-keys project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))]
        (html
         [:div.add-key
          [:h4 "Add user key"]
          [:p
           "If a deploy key can't access all of your project's private dependencies, we can configure it to use an SSH key with the same level of access to GitHub repositories that you have."]
          [:div.authorization
           (if-not (user-model/public-key-scope? user)
             (list
              [:p "In order to do so, you'll need to grant authorization from GitHub to the \"admin:public_key\" scope. This will allow us to add a new authorized public key to your GitHub account."]
              (button/link {:href (gh-utils/auth-url :scope ["admin:public_key" "user:email" "repo"])
                            :kind :primary}
                           "Authorize with GitHub"))
             [:div.request-user
              (forms/managed-button
               [:input.btn
                {:tooltip "{ title: 'Create a new user key for this project, with access to all of the projects of your GitHub account.', animation: false }"
                 :type "submit"
                 :on-click #(raise! owner [:new-checkout-key-clicked {:project-id project-id
                                                                      :project-name project-name
                                                                      :key-type "github-user-key"}])
                 :value (str "Create and add " (:login user) " user key")
                 :data-loading-text "Saving..."
                 :data-success-text "Saved"}])])]])))))

(defmethod add-user-key-section :bitbucket [data owner]
  (reify
    om/IRender
    (render [_]
      (let [user (:user data)
            project-data (:project-data data)
            project (:project project-data)
            checkout-keys (:checkout-keys project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))]
        (html
         [:div.add-key
          [:h4 "Create user key"]
          [:p
           "If a deploy key can't access all of your project's private dependencies, we can help you setup an SSH key with the same level of access to Bitbucket repositories that you have."]
          [:div.authorization
           [:div.request-user
            (forms/managed-button
             [:input.btn
              {:tooltip "{ title: 'Create a new user key for this project, with access to all of the projects of your Bitbucket account.', animation: false }"
               :type "submit"
               :on-click #(raise! owner [:new-checkout-key-clicked {:project-id project-id
                                                                    :project-name project-name
                                                                    :key-type "bitbucket-user-key"}])
               :value (str "Create " (:login user) " user key")
               :data-loading-text "Creating..."
               :data-success-text "Created"}])]]])))))

(defn bitbucket-add-user-key-instructions [data owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [user key]} data]
        (html
         [:div.add-key
          [:h4 "Add user key to Bitbucket"]
          [:p "You now need to add this key to your "
           [:a {:href
                (str "https://bitbucket.org/account/user/"
                     (-> user :bitbucket :login)
                     "/ssh-keys/")}
            "Bitbucket account SSH keys"]]
          [:p
           [:ol
            [:li "Navigate to your " [:a {:href (checkout-key-link key nil user)}
                                      "Bitbucket account SSH keys"]]
            [:li "Click \"Add key\""]
            [:li
             "Copy this public key and paste it into the new key on Bitbucket"
             [:pre.wrap-words
              (:public_key key)]]
            [:li "Save the new key"]]]])))))

(defn checkout-ssh-keys [data owner]
  (reify
    om/IRender
    (render [_]
      (let [project-data (:project-data data)
            user (:user data)
            project (:project project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))
            vcs-name (-> project :vcs-type utils/prettify-vcs_type)
            checkout-keys (:checkout-keys project-data)]
        (html
         [:section.checkout-page
          [:article
           [:legend "Checkout keys for " project-name]
           [:div.checkout-page-inner
            (if (nil? checkout-keys)
              (spinner)

              [:div
               (if-not (seq checkout-keys)
                 [:p "No checkout key is currently configured! We won't be able to check out your project for testing :("]
                 [:div
                  [:p
                   "Here are the keys we can currently use to check out your project, submodules, "
                   "and private " vcs-name " dependencies. The currently preferred key is marked, but "
                   "we will automatically fall back to the other keys if the preferred key is revoked."]
                  (om/build table/table
                            {:rows checkout-keys
                             :key-fn :fingerprint
                             :columns [{:header "Description"
                                        :cell-fn #(if-let [vcs-link (checkout-key-link % project user)]
                                                    [:a {:href vcs-link :target "_blank"}
                                                     (checkout-key-description % project) " "
                                                     (case (vcs-url/vcs-type project-id)
                                                       "bitbucket" [:i.fa.fa-bitbucket]
                                                       "github" [:i.fa.fa-github])]
                                                    (checkout-key-description % project))}
                                       {:header "Fingerprint"
                                        :cell-fn :fingerprint}
                                       {:header "Preferred"
                                        :type #{:right :shrink}
                                        :cell-fn #(when (:preferred %)
                                                    (html [:i.material-icons "done"]))}
                                       {:header "Remove"
                                        :type #{:shrink :right}
                                        :cell-fn
                                        (fn [key]
                                          (table/action-button
                                           "Remove"
                                           (icon/cancel-circle)
                                           #(raise! owner [:delete-checkout-key-clicked {:project-id project-id
                                                                                         :project-name project-name
                                                                                         :fingerprint (:fingerprint key)}])))}]})])
               (when-not (seq (filter #(= "deploy-key" (:type %)) checkout-keys))
                 [:div.add-key
                  [:h4 "Add deploy key"]
                  [:p
                   "Deploy keys are the best option for most projects: they only have access to a single " vcs-name " repository."]
                  [:div.request-user
                   (button/managed-button
                    {:on-click #(raise! owner
                                        [:new-checkout-key-clicked {:project-id project-id
                                                                    :project-name project-name
                                                                    :key-type "deploy-key"}])
                     :loading-text "Saving..."
                     :success-text "Saved"}
                    "Add Deploy Key")]])
               (when-not (some #{"github-user-key" "bitbucket-user-key"} (map :type checkout-keys))
                 (om/build add-user-key-section data))
               (when-let [bb-user-key (first (filter (fn [key] (and
                                                                (= (:type key)
                                                                   "bitbucket-user-key")
                                                                (= (:login key)
                                                                   (-> user :bitbucket :login))))
                                                     checkout-keys))]
                 (om/build bitbucket-add-user-key-instructions {:user user :key bb-user-key}))


               [:div.help-block
                [:h2 "About checkout keys"]
                [:h4 "What is a deploy key?"]
                [:p "A deploy key is a repo-specific SSH key. " vcs-name " has the public key, and we store the private key. The deployment key gives CircleCI access to a single repository."]
                [:p "If you want to push to your repository from builds, please add a user key as described below or manually add " [:a {:href "https://circleci.com/docs/adding-read-write-deployment-key/"} "read-write deployment key"]"."]
                [:h4 "What is a user key?"]
                [:p "A user key is a user-specific SSH key. " vcs-name " has the public key, and we store the private key. Possession of the private key gives the ability to act as that user, for purposes of 'git' access to projects."]
                [:h4 "How are these keys used?"]
                [:p "When we build your project, we install the private key into the .ssh directory, and configure ssh to use it when communicating with your version control provider. Therefore, it gets used for:"]
                [:ul
                 [:li "checking out the main project"]
                 [:li "checking out any " vcs-name "-hosted submodules"]
                 [:li "checking out any " vcs-name "-hosted private dependencies"]
                 [:li "automatic git merging/tagging/etc."]]
                [:p]
                [:p "That's why a deploy key isn't sufficiently powerful for projects with additional private dependencies!"]
                [:h4 "What about security?"]
                [:p "The private keys of the checkout keypairs we generate never leave our systems (only the public key is transmitted to" vcs-name "), and are safely encrypted in storage. However, since they are installed into your build containers, any code that you run in CircleCI can read them. You shouldn't push untrusted code to CircleCI!"]
                [:h4 "Isn't there a middle ground between deploy keys and user keys?"]
                [:p "Not really :("]
                [:p "Deploy keys and user keys are the only key types that " vcs-name " supports. Deploy keys are globally unique (i.e. there's no way to make a deploy key with access to multiple repositories) and user keys have no notion of \\scope\\ separate from the user they're associated with."]
                [:p "Your best bet, for fine-grained access to more than one repo, is to create what GitHub calls a "
                 [:a {:href "https://help.github.com/articles/managing-deploy-keys#machine-users"} "machine user"]
                 ". Give this user exactly the permissions your build requires, and then associate its user key with your project on CircleCI."
                 (when (= "bitbucket" (:vcs-type project))
                   "The same technique can be applied for Bitbucket repositories.")]]])]]])))))

(defn scope-popover-html []
  ;; nb that this is a bad idea in general, but should be ok for rarely used popovers
  (hiccup->html-str
   [:div
    [:p "A token's scope limits what can be done with it."]

    [:h5 "Status"]
    [:p
     "Allows read-only access to the build status (passing, failing, etc) of any branch of the project. Its intended use is "
     [:a {:target "_blank" :href "https://circleci.com/docs/status-badges/"} "sharing status badges"]
     " and "
     [:a {:target "_blank", :href "https://circleci.com/docs/polling-project-status/"} "status polling tools"]
     " for private projects."]

    [:h5 "Build Artifacts"]
    [:p "Allows read-only access to build artifacts of any branch of the project. Its intended use is for serving files to deployment systems."]

    [:h5 "All"]
    [:p "Allows full read-write access to this project in CircleCI. It is intended for full-fledged API clients which only need to access a single project."]]))

(defn api-tokens [project-data owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})
    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [project (:project project-data)
            project-id (project-model/id project)
            {:keys [scope label]
             :or {scope "status" label ""}} (:new-api-token project-data)]
        (html
         [:section.circle-api-page
          [:article
           [:legend "API Permissions"]
           (card/titled
            {:title (str "API tokens for " (vcs-url/project-name (:vcs_url project)))
             :action (button/button {:on-click #(om/set-state! owner :show-modal? true)
                                     :kind :primary
                                     :size :small}
                                    "Create Token")}
            (html
             [:div
              [:p "Create and revoke project-specific API tokens to access this project's details using our API."]
              (when show-modal?
                (let [close-fn #(om/set-state! owner :show-modal? false)]
                  (modal/modal-dialog {:title "Add an API token"
                                       :body (html
                                              [:div
                                               [:p "First choose a scope and then create a label."]
                                               (form/form {}
                                                          (dropdown/dropdown {:options [["status" "Status"]
                                                                                        ["view-builds" "Build Artifacts"]
                                                                                        ["all" "All"]]
                                                                              :on-change #(raise! owner [:set-project-api-token-scope {:scope %}])
                                                                              :name "scope"
                                                                              :value scope})
                                                          (om/build form/text-field {:value (str label)
                                                                                     :on-change #(utils/edit-input owner
                                                                                                                   (conj state/project-data-path
                                                                                                                         :new-api-token
                                                                                                                         :label)
                                                                                                                   %)
                                                                                     :label "Token Label"}))])
                                       :close-fn close-fn
                                       :actions [(button/button {:on-click close-fn
                                                                 :kind :flat}
                                                                "Cancel")
                                                 (button/managed-button
                                                  {:failed-text  "Failed"
                                                   :success-text "Added"
                                                   :loading-text "Adding..."
                                                   :on-click #(raise! owner [:saved-project-api-token {:project-id project-id
                                                                                                       :api-token {:scope scope
                                                                                                                   :label label}
                                                                                                       :on-success close-fn}])
                                                   :kind :primary}
                                                  "Add Token")]})))
              (when-let [tokens (seq (:tokens project-data))]
                (om/build table/table
                          {:rows tokens
                           :key-fn :token
                           :columns [{:header "Scope"
                                      :cell-fn :scope}
                                     {:header "Label"
                                      :cell-fn :label}
                                     {:header "Token"
                                      :cell-fn :token}
                                     {:header "Created"
                                      :cell-fn :time}
                                     {:header "Remove"
                                      :type #{:shrink :right}
                                      :cell-fn
                                      (fn [token]
                                        (om/build remove-action-button
                                                  {:confirmation-question
                                                   (str "Are you sure you want to remove the API token \""
                                                        (:label token)
                                                        "\"?")
                                                   :remove-fn
                                                   #(raise! owner [:deleted-project-api-token {:project-id project-id
                                                                                               :token (:token token)}])}))}]}))]))]])))))

(defn heroku [data owner]
  (reify
    om/IRender
    (render [_]
      (let [project-data (:project-data data)
            user (:user data)
            project (:project project-data)
            project-id (project-model/id project)
            login (:login user)]
        (html
         [:section.heroku-api
          [:article
           [:legend "Heroku API Key"]
           [:div.heroku-step
            [:h4 "Step 1: Heroku API key"]
            [:div (when (:heroku_api_key user)
                    [:p "Your Heroku key is entered. Great!"])
             [:p (:heroku_api_key user)]
             [:div
              [:p
               "You can set your Heroku key in your "
               [:a {:href "/account/heroku"} "user settings"] "."]]]]
           [:div.heroku-step
            [:h4 "Step 2: Associate a Heroku SSH key with your account"]
            [:span "Current deploy user: "
             [:strong (or (:heroku_deploy_user project) "none") " "]
             [:i.fa.fa-question-circle
              {:data-bind "tooltip: {}",
               :title "This will affect all deploys on this project. Skipping this step will result in permission denied errors when deploying."}]]
            [:form.api
             (if (= (:heroku_deploy_user project) (:login user))
               (button/managed-button
                {:kind :primary
                 :success-text "Saved"
                 :loading-text "Saving..."
                 :on-click #(raise! owner [:removed-heroku-deploy-user {:project-id project-id}])}
                "Remove Deploy User")

               (button/managed-button
                {:success-text "Saved"
                 :loading-text "Saving..."
                 :on-click #(raise! owner [:set-heroku-deploy-user {:project-id project-id
                                                                    :login login}])
                 :kind :primary}
                (str "Set User to " (:login user))))]]
           [:div.heroku-step
            [:h4
             "Step 3: Add deployment settings to your "
             [:a {:href "https://circleci.com/docs/configuration/#deployment"} "circle.yml file"] " (example below)."]
            [:pre
             [:code
              "deployment:\n"
              "  staging:\n"
              "    branch: master\n"
              "    heroku:\n"
              "      appname: foo-bar-123"]]]]])))))

(defn other-deployment [project-data owner]
  (om/component
   (html
    [:section
     [:article
      [:legend
       "Other deployments for " (vcs-url/project-name (get-in project-data [:project :vcs_url]))]
      [:div.doc
       [:p "CircleCI supports deploying to any server, using custom commands. See "
        [:a {:target "_blank",
             :href "https://circleci.com/docs/configuration#deployment"}
         "our deployment documentation"]
        " to set it up."]]]])))

(defn aws-keys-form [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            inputs (inputs/get-inputs-from-app-state owner)

            settings (utils/deep-merge (get-in project [:aws :keypair])
                                       (get-in inputs [:aws :keypair]))
            {:keys [access_key_id secret_access_key]} settings

            project-id (project-model/id project)
            input-path (fn [& ks] (apply conj state/inputs-path :aws :keypair ks))]
        (html
         [:div.aws-page-inner
          [:p "Set the AWS keypair to be used for authenticating against AWS services during your builds. "
           "Credentials are installed on your containers into the " [:code "~/.aws/config"] " and "
           [:code "~/.aws/credentials"] " properties files. These are read by common AWS libraries such as "
           [:a {:href "http://aws.amazon.com/documentation/sdk-for-java/"} "the Java SDK"] ", "
           [:a {:href "https://boto.readthedocs.org/en/latest/"} "Python's boto"] ", and "
           [:a {:href "http://rubygems.org/gems/aws-sdk"} "the Ruby SDK"] "."]
          [:p "We recommend that you create a unique "
           [:a {:href "http://docs.aws.amazon.com/general/latest/gr/root-vs-iam.html"} "IAM user"]
           " for use by CircleCI."]
          [:form
           [:input#access-key-id
            {:required true
             :auto-focus true
             :type "text"
             :value (or access_key_id "")
             :on-change #(utils/edit-input owner (input-path :access_key_id) %)}]
           [:label {:placeholder "Access Key ID"}]

           [:input#secret-access-key
            {:required true,
             :type "text",
             :value (or secret_access_key "")
             :auto-complete "off"
             :on-change #(utils/edit-input owner (input-path :secret_access_key) %)}]
           [:label {:placeholder "Secret Access Key"}]

           [:div.buttons
            (forms/managed-button
             [(if (and access_key_id secret_access_key) :input.save :input)
              {:data-failed-text "Failed"
               :data-success-text "Saved"
               :data-loading-text "Saving..."
               :value "Save AWS keys"
               :type "submit"
               :on-click #(raise! owner [:saved-project-settings {:project-id project-id :merge-paths [[:aws :keypair]]}])}])
            (when (and access_key_id secret_access_key)
              (forms/managed-button
               [:input.remove {:data-failed-text "Failed"
                               :data-success-text "Cleared"
                               :data-loading-text "Clearing..."
                               :value "Clear AWS keys"
                               :type "submit"
                               :on-click #(do
                                            (raise! owner [:edited-input {:path (input-path) :value nil}])
                                            (raise! owner [:saved-project-settings {:project-id project-id}]))}]))]]])))))

(defn aws [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)]
        (html
         [:section.aws-page
          [:article
           [:legend "AWS keys for " (vcs-url/project-name (:vcs_url project))]
           (om/build aws-keys-form project-data)]])))))

(defn- jira-input-path [& segments]
  (into state/inputs-path (cons :jira segments)))

(defn jira-basic-modal
  [project owner]
  (let [project-id (project-model/id project)
        inputs (inputs/get-inputs-from-app-state owner)
        credentials (:jira inputs)
        track-event! (fn [event-type]
                       ((om/get-shared owner :track-event)
                        {:event-type event-type
                         :properties {:component "jira-settings"
                                      :auth-type "basic"}}))
        close-modal! #(om/set-state! owner :modal nil)
        close-fn (fn [_]
                   (close-modal!)
                   (track-event! :cancel-clicked))]
    (modal/modal-dialog
     {:title "Add JIRA credentials"
      :body (html
             [:div
              [:p "Configure JIRA to create issues from CircleCI’s build page."]
              (form/form {}
                         (om/build form/text-field {:label "JIRA username"
                                                    :value (:username credentials)
                                                    :on-change #(utils/edit-input owner (jira-input-path :username) %)})
                         (om/build form/text-field {:label "JIRA password"
                                                    :password? true
                                                    :value (:password credentials)
                                                    :on-change #(utils/edit-input owner (jira-input-path :password) %)})
                         (om/build form/text-field {:label "JIRA base hostname"
                                                    :value (:base_url credentials)
                                                    :on-change #(utils/edit-input owner (jira-input-path :base_url) %)}))])
      :actions [(button/button {:on-click close-fn
                                :kind :flat}
                               "Cancel")
                (button/managed-button
                 {:failed-text "Failed"
                  :success-text "Saved"
                  :loading-text "Saving..."
                  :kind :primary
                  :on-click
                  (fn [_]
                    (raise! owner
                            [:saved-project-settings {:project-id project-id
                                                      :merge-paths [[:jira]]
                                                      :on-success close-modal!
                                                      :flash-context :jira-added}])
                    (track-event! :save-clicked))}
                 "Save")]
      :close-fn close-fn})))

(defn jira-connect-modal
  [project owner]
  (let [inputs (inputs/get-inputs-from-app-state owner)
        token (get-in inputs [:jira :circle_token])
        track-event (fn [event-type]
                      ((om/get-shared owner :track-event)
                       {:event-type event-type
                        :properties {:component "jira-settings"
                                     :auth-type "connect"}}))
        close-modal! #(om/set-state! owner :modal nil)
        close-fn (fn [_]
                   (close-modal!)
                   (track-event :cancel-clicked))]
    (modal/modal-dialog
     {:title "Add a JIRA token"
      :body (html
             [:div
              [:p "Configure JIRA to create issues from CircleCI’s build page."]
              (form/form {}
                         (om/build form/text-field
                                   {:label "Token"
                                    :require true
                                    :value token
                                    :on-change #(utils/edit-input owner
                                                                  (jira-input-path :circle_token)
                                                                  %)}))])
      :actions [(button/button {:on-click close-fn
                                :kind :flat}
                               "Cancel")
                (button/managed-button
                 {:failed-text "Failed"
                  :success-text "Saved"
                  :loading-text "Saving..."
                  :kind :primary
                  :on-click
                  (fn [_]
                    (raise! owner
                            [:saved-project-settings {:project-id (project-model/id project)
                                                      :merge-paths [[:jira]]
                                                      :on-success close-modal!
                                                      :flash-context :jira-added}])
                    (track-event :save-clicked))}
                 "Save")]
      :close-fn close-fn})))

(defn jira-empty-state [project owner]
  (reify
    om/IInitState
    (init-state [_]
      {:modal nil})
    om/IRenderState
    (render-state [_ {:keys [modal]}]
      (component
        (html
         [:div
          (case modal
            :basic (jira-basic-modal project owner)
            :connect (jira-connect-modal project owner)
            nil)
          (when (config/jira-connect-enabled?)
            [:p.intro "There are 2 options for setting up JIRA with CircleCI: Atlassian Connect or JIRA Basic Authentication. Choose one of the options below to continue."])
          (letfn [(track-event [auth-type]
                    ((om/get-shared owner :track-event)
                     {:event-type :add-credentials-clicked
                      :properties {:component "jira-integration"
                                   :auth-type auth-type}}))]
            (element :column-group
              (html
               [:div
                (when (config/jira-connect-enabled?)
                  [:.column
                   [:.title "Atlassian Connect for CircleCI"]
                   [:p "If you are an Atlassian administrator you can get a token by following the instructions "
                    [:a
                     {:href "https://marketplace.atlassian.com/plugins/circleci.jira/cloud/overview"
                      :target "_blank"}
                     "here"]
                    "."]
                   (button/button {:kind :primary
                                   :fixed? true
                                   :on-click (fn [_]
                                               (om/set-state! owner :modal :connect)
                                               (track-event "connect"))}
                                  "Add Token")])
                [:div.column
                 (when (config/jira-connect-enabled?)
                   [:.title "JIRA Basic Authentication"])
                 [:p (str
                      (if (config/jira-connect-enabled?)
                        "If you are not an Atlassian administrator you can s"
                        "S")
                      "tore your JIRA username, password, and JIRA base hostname to connect JIRA and CircleCI.")]
                 (button/button {:kind :primary
                                 :fixed? true
                                 :on-click (fn [_]
                                             (om/set-state! owner :modal :basic)
                                             (track-event "basic"))}
                                "Add Credentials")]])))])))))

(defmulti jira-installed
  "View to display when jira credentials are installed on the project."
  (fn [project _]
    (if (get-in project [:jira :username])
      :basic
      :connect)))

(defmethod jira-installed :basic [project owner]
  (html
   [:div
    [:p
     (str
      "Connected with JIRA Basic Authentication. "
      (if (config/jira-connect-enabled?)
        "To set up JIRA with Atlassian Connect,"
        "To set up JIRA with different credentials,")
      " remove the integration below first.")]
    (let [credentials (:jira project)
          linkify (fn [url]
                    (html
                     [:a {:href url
                          :target "_blank"} url]))]
      (om/build table/table
                {:rows [credentials]
                 :key-fn :username
                 :columns [{:header "JIRA base hostname"
                            :cell-fn (comp linkify :base_url)}
                           {:header "JIRA username"
                            :cell-fn :username}
                           {:header "Remove"
                            :type #{:shrink :right}
                            :cell-fn
                            (fn [{:keys [base_url]}]
                              (om/build remove-action-button
                                        {:confirmation-question
                                         (html
                                          [:p "Are you sure you want to remove the integration for " (linkify base_url) "?"])
                                         :remove-fn #(do
                                                       (raise! owner [:edited-input {:path (jira-input-path)
                                                                                     :value nil}])
                                                       (raise! owner [:saved-project-settings {:project-id (project-model/id project)
                                                                                               :merge-paths [[:jira]]
                                                                                               :flash-context :jira-removed}]))}))}]}))]))

(defmethod jira-installed :connect [project owner]
  (html
   [:div
    [:p "Connected with Atlassian Connect. To set up JIRA with Basic Authentication, remove the integration below first."]
    (let [credentials (:jira project)
          linkify (fn [url]
                    (html
                     [:a {:href url
                          :target "_blank"} url]))]
      (om/build table/table
                {:rows [credentials]
                 :key-fn :circle_token
                 :columns [{:header "JIRA base hostname"
                            :cell-fn (comp linkify :base_url)}
                           {:header "Token"
                            :cell-fn :circle_token}
                           {:header "Remove"
                            :type #{:shrink :right}
                            :cell-fn
                            (fn [{:keys [base_url]}]
                              (om/build remove-action-button
                                        {:confirmation-question
                                         (html
                                          [:p "Are you sure you want to remove the integration for " (linkify base_url) "?"])
                                         :remove-fn #(let [input-path (jira-input-path :circle_token)]
                                                       (raise! owner [:edited-input {:path input-path
                                                                                     :value nil}])
                                                       (raise! owner [:saved-project-settings {:project-id (project-model/id project)
                                                                                               :merge-paths [input-path]
                                                                                               :flash-context :jira-removed}]))}))}]}))]))

(defn jira-integration [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)]
        (html
         [:section
          [:article
           (card/titled
            {:title (str "JIRA integration for " (vcs-url/project-name (:vcs_url project)))}
            (if (:jira project)
              (jira-installed project owner)
              (om/build jira-empty-state project)))]])))))

(defn aws-codedeploy-app-name [project-data owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div
        [:form
         [:input#application-name
          {:required true, :type "text"
           :on-change #(utils/edit-input owner (conj state/inputs-path :project-settings-codedeploy-app-name) %)}]
         [:label {:placeholder "Application Name"}]
         [:input {:value "Add app settings",
                  :type "submit"
                  :on-click #(raise! owner [:new-codedeploy-app-name-entered])}]]]))))

(defn aws-codedeploy-app-details [project-data owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [project (:project project-data)
            applications (get-in project [:aws :services :codedeploy])
            [app-name _] (first applications)]
        (utils/popover "#app-root-popover-hack"
                       {:html true :delay 0 :animation false
                        :placement "right" :title "Application Root"
                        :content (hiccup->html-str [:p "The directory in your project to package up into an application revision. "
                                                    "This is relative to your project's root, " [:code "/"] " means the projects's root "
                                                    "directory, " [:code "/app"] " means the app directory in your project's root "
                                                    "directory."])})
        (utils/popover "#bucket-popover-hack"
                       {:html true :delay 0 :animation false
                        :placement "right" :title "Revision Location: Bucket Name"
                        :content (hiccup->html-str [:p "The name of the S3 bucket CircleCI should store application revisions for \"" (name app-name) "\" in."])})
        (utils/popover "#key-pattern-popover-hack"
                       {:html true :delay 0 :animation false
                        :placement "right" :title "Revision Location: Key Pattern"
                        :content (hiccup->html-str [:p "A template used to construct S3 keys for storing application revisions."
                                                    "You can use " [:a {:href "https://circleci.com/docs/continuous-deployment-with-aws-codedeploy/#key-patterns"} "substitution variables"]
                                                    " in the Key Pattern to generate a unique key for each build."])})))
    om/IRender
    (render [_]
      (let [project (:project project-data)
            inputs (inputs/get-inputs-from-app-state owner)
            applications (utils/deep-merge (get-in project [:aws :services :codedeploy])
                                           (get-in inputs [:aws :services :codedeploy]))

            [app-name settings] (first applications)
            {:keys [bucket key_pattern]} (-> settings :revision_location :s3_location)
            application-root (:application_root settings)
            aws-region (:region settings)

            project-id (project-model/id project)
            input-path (fn [& ks] (apply conj state/inputs-path :aws :services :codedeploy ks))]
        (html
         [:form
          [:legend (name app-name)]

          [:fieldset
           [:select.form-control {:class (when (not aws-region) "placeholder")
                                  :value (or aws-region "")
                                  ;; Updates the project cursor in order to trigger a re-render
                                  :on-change #(utils/edit-input owner (conj state/project-path :aws :services :codedeploy app-name :region) %)}
            ;; From http://docs.aws.amazon.com/general/latest/gr/rande.html#codedeploy_region
            [:option {:value ""} "Choose AWS Region..."]
            [:option {:disabled "disabled"} "-----"]
            [:option {:value "us-east-1"} "us-east-1"]  ;; US East (N. Virginia)
            [:option {:value "us-east-2"} "us-east-2"]  ;; US East (Ohio)
            [:option {:value "us-west-1"} "us-west-1"]  ;; US West (N. California)
            [:option {:value "us-west-2"} "us-west-2"]  ;; US West (Oregon)
            [:option {:value "ca-central-1"} "ca-central-1"]  ;; Canada (Central)
            [:option {:value "ap-south-1"} "ap-south-1"] ;; Asia Pacific (Mumbai)
            [:option {:value "sa-east-1"} "sa-east-1"]  ;; South America (São Paulo)
            [:option {:value "eu-west-1"} "eu-west-1"]  ;; EU (Ireland)
            [:option {:value "eu-west-2"} "eu-west-2"]  ;; EU (London)
            [:option {:value "eu-central-1"} "eu-central-1"]  ;; EU (Frankfurt)
            [:option {:value "ap-northeast-1"} "ap-northeast-1"]  ;; Asia Pacific (Tokyo)
            [:option {:value "ap-northeast-2"} "ap-northeast-2"]  ;; Asia Pacific (Seoul)
            [:option {:value "ap-southeast-1"} "ap-southeast-1"]  ;; Asia Pacific (Singapore)
            [:option {:value "ap-southeast-2"} "ap-southeast-2"]  ;; Asia Pacific (Sydney)
            [:option {:value "cn-north-1"} "cn-north-1"]]  ;; China (Beijing)

           [:div.input-with-help
            [:input#application-root
             {:required true, :type "text", :value (or application-root "")
              :on-change #(utils/edit-input owner (input-path app-name :application_root) %)}]
            [:label {:placeholder "Application Root"}]
            [:i.fa.fa-question-circle#app-root-popover-hack {:title "Application Root"}]]]

          [:fieldset
           [:h5 "Revision Location"]
           [:div.input-with-help
            [:input#s3-bucket
             {:required true, :type "text", :value (or bucket "")
              :on-change #(utils/edit-input owner (input-path app-name :revision_location :s3_location :bucket) %)}]
            [:label {:placeholder "Bucket Name"}]
            [:i.fa.fa-question-circle#bucket-popover-hack {:title "S3 Bucket Name"}]]

           [:div.input-with-help
            [:input#s3-key-prefix
             {:required true, :type "text", :value (or key_pattern "")
              :on-change #(utils/edit-input owner (input-path app-name :revision_location :s3_location :key_pattern) %)}]
            [:label {:placeholder "Key Pattern"}]
            [:i.fa.fa-question-circle#key-pattern-popover-hack {:title "S3 Key Pattern"}]]]

          [:div.buttons
           (forms/managed-button
            [:input.save {:data-failed-text "Failed",
                          :data-success-text "Saved",
                          :data-loading-text "Saving...",
                          :value "Save app",
                          :type "submit"
                          :on-click #(do
                                       (raise! owner [:edited-input {:path (input-path app-name :revision_location :revision_type) :value "S3"}])
                                       (raise! owner [:saved-project-settings {:project-id project-id
                                                                               :merge-paths [[:aws :services :codedeploy]]}]))}])
           (forms/managed-button
            [:input.remove {:data-failed-text "Failed",
                            :data-success-text "Removed",
                            :data-loading-text "Removing...",
                            :value "Remove app",
                            :type "submit"
                            :on-click #(do
                                         (raise! owner [:edited-input {:path (input-path) :value nil}])
                                         (raise! owner [:saved-project-settings {:project-id project-id}]))}])]])))))

(defn aws-codedeploy [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            applications (get-in project [:aws :services :codedeploy])
            app-name (some-> applications first key)]
        (html
         [:section.aws-codedeploy
          [:article
           [:legend "CodeDeploy application settings for " (vcs-url/project-name (:vcs_url project))]
           [:p "CodeDeploy is an AWS service for deploying to your EC2 instances. "
            "Check out our " [:a {:href "https://circleci.com/docs/continuous-deployment-with-aws-codedeploy/"} "getting started with CodeDeploy"]
            " guide for detailed information on getting set up."]
           [:div.aws-page-inner
            [:div.aws-codedeploy-step
             [:h4 "Step 1"]
             (om/build aws-keys-form project-data)]

            [:div.aws-codedeploy-step
             [:h4 "Step 2"]
             [:p "[Optional] Configure application-wide settings."]
             [:p "This is useful if you deploy the same app to multiple deployment groups "
              "(e.g. staging, production) depending on which branch was built. "
              "With application settings configured in the UI you only need to set the "
              "deployment group and, optionally, deployment configuration, in each deployment "
              "block in your " [:a {:href "https://circleci.com/docs/configuration/#deployment"} "circle.yml file"] ". "
              "If you skip this step you will need to add all deployment settings into your circle.yml file."]
             (if (not (seq applications))
               ;; No settings set, need to get the application name first
               (om/build aws-codedeploy-app-name project-data)
               ;; Once we have an application name we can accept the rest of the settings
               (om/build aws-codedeploy-app-details project-data))]
            [:div.aws-codedeploy-step
             [:h4 "Step 3"]
             [:p "Add deployment settings to your "
              [:a {:href "https://circleci.com/docs/configuration/#deployment"} "circle.yml file"]
              " (example below)."]
             [:pre
              [:code
               "deployment:\n"
               "  staging:\n"
               "    branch: master\n"
               "    codedeploy:\n"
               (if app-name
                 (str "      " (name app-name) ":\n"
                      "        deployment_group: my-deployment-group\n")
                 (str "      appname-1234:\n"
                      "        application_root: /\n"
                      "        region: us-east-1\n"
                      "        revision_location:\n"
                      "          revision_type: S3\n"
                      "          s3_location:\n"
                      "            bucket: my-bucket\n"
                      "            key_pattern: appname-1234-{BRANCH}-{SHORT_COMMIT}\n"
                      "        deployment_group: my-deployment-group\n"))]]]]]])))))

(defn p12-key-table [{:keys [rows]} owner]
  (reify
    om/IRender
    (render [_]
      (om/build table/table
                {:rows rows
                 :key-fn :id
                 :columns [{:header "Description"
                            :cell-fn :description}
                           {:header "Filename"
                            :type :shrink
                            :cell-fn :filename}
                           {:header "ID"
                            :type :shrink
                            :cell-fn :id}
                           {:header "Uploaded"
                            :type :shrink
                            :cell-fn (comp datetime/as-time-since :uploaded_at)}
                           {:header "Remove"
                            :type #{:shrink :right}
                            :cell-fn (fn [key]
                                       (om/build remove-action-button
                                                 {:confirmation-question
                                                  (str
                                                   "Are you sure you want to remove the \""
                                                   (:description key)
                                                   "\" Apple Code Signing Key?")

                                                  :remove-fn
                                                  #(raise! owner
                                                           [:delete-p12
                                                            (select-keys key [:project-name :vcs-type :id])])}))}]}))))

(defn provisioning-profiles-table [{:keys [rows]} owner]
  (reify
    om/IRender
    (render [_]
      (om/build table/table
                {:rows rows
                 :key-fn :uuid
                 :columns [{:header "Description"
                            :cell-fn :description}
                           {:header "Filename"
                            :type :shrink
                            :cell-fn :filename}
                           {:header "UUID"
                            :type :shrink
                            :cell-fn :uuid}
                           {:header "Uploaded"
                            :type :shrink
                            :cell-fn (comp datetime/as-time-since :uploaded_at)}
                           {:header "Remove"
                            :type #{:shrink :right}
                            :cell-fn (fn [profile]
                                       (om/build remove-action-button
                                                 {:confirmation-question
                                                  (str
                                                   "Are you sure you want to remove the \""
                                                   (:description profile)
                                                   "\" provisioning profile?")

                                                  :remove-fn
                                                  #(raise! owner
                                                           [:delete-provisioning-profile
                                                            (select-keys profile [:project-name :vcs-type :uuid])])}))}]}))))

(defn p12-upload-modal [{:keys [close-fn error-message project-name vcs-type]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:description nil
       :password nil
       :file-name nil
       :file-content nil})

    om/IRenderState
    (render-state [_ {:keys [description password file-name file-content]}]
      (modal/modal-dialog
       {:title "Upload a New Apple Code Signing Key"
        :body
        (html
         [:div
          (om/build common/flashes error-message)
          (form/form {}
                     (om/build form/text-field {:label "Description"
                                                :value description
                                                :on-change #(om/set-state! owner :description (.. % -target -value))})
                     (om/build form/text-field {:label "Password (Optional)"
                                                :password? true
                                                :value password
                                                :on-change #(om/set-state! owner :password (.. % -target -value))})
                     (om/build form/file-selector {:label "Key File"
                                                   :file-name file-name
                                                   :on-change (fn [{:keys [file-name file-content]}]
                                                                (om/update-state! owner #(merge % {:file-name file-name
                                                                                                   :file-content file-content})))}))])
        :actions [(forms/managed-button
                   [:input.upload-p12-button
                    {:data-failed-text "Failed" ,
                     :data-success-text "Uploaded" ,
                     :data-loading-text "Uploading..." ,
                     :value "Upload" ,
                     :type "submit"
                     :disabled (not (and file-content description))
                     :on-click #(raise! owner [:upload-p12 {:project-name project-name
                                                            :vcs-type vcs-type
                                                            :description description
                                                            :password (or password "")
                                                            :file-content (base64/encodeString file-content)
                                                            :file-name file-name
                                                            :on-success close-fn}])}])]
        :close-fn close-fn}))))

(defn provisioning-profile-upload-modal [{:keys [close-fn error-message project-name vcs-type]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:description nil
       :file-name nil
       :file-content nil})

    om/IRenderState
    (render-state [_ {:keys [description file-name file-content]}]
      (modal/modal-dialog
       {:title "Upload a New Provisioning Profile"
        :body
        (html
         [:div
          (om/build common/flashes error-message)
          (form/form {}
                     (om/build form/text-field {:label "Description"
                                                :value description
                                                :on-change #(om/set-state! owner :description (.. % -target -value))})
                     (om/build form/file-selector {:label "Provisioning Profile"
                                                   :file-name file-name
                                                   :on-change (fn [{:keys [file-name file-content]}]
                                                                (om/update-state! owner #(merge % {:file-name file-name
                                                                                                   :file-content file-content})))}))])
        :actions [(button/managed-button {:failed-text "Failed"
                                          :success-text "Uploaded"
                                          :loading-text "Uploading..."
                                          :disabled? (not (and file-content description))
                                          :kind :primary
                                          :on-click #(raise! owner [:upload-provisioning-profile {:project-name project-name
                                                                                                  :vcs-type vcs-type
                                                                                                  :description description
                                                                                                  :file-content (base64/encodeString file-content)
                                                                                                  :file-name file-name
                                                                                                  :on-success close-fn}])}
                                         "Upload")]
        :close-fn close-fn}))))



(defn code-signing-keys [{:keys [project-data error-message]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})
    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [{:keys [project osx-keys osx-profiles]} project-data
            project-name (vcs-url/project-name (:vcs_url project))
            vcs-type (project-model/vcs-type project)]
        (html
         [:section.code-signing-page {:data-component `code-signing-keys}
          [:article
           [:div.header
            [:div.title "Apple Code Signing Keys"]
            (button/button
             {:on-click #(om/set-state! owner :show-modal? true)
              :kind :primary}
             "Upload Key")]
           [:hr.divider]
           [:div.info "The following code-signing identities will be added to the system keychain when your build
                        begins, and will be available to sign iOS and OS X apps. For more information about code-signing
                        on CircleCI see our "
            [:a
             {:href "https://circleci.com/docs/ios-code-signing/"}
             "code-signing documentation."]]
           (if-not (empty? osx-keys)
             (om/build p12-key-table {:rows (->> osx-keys
                                                 (map (partial merge {:project-name project-name
                                                                      :vcs-type vcs-type})))})
             (empty-state/empty-state {:icon (icon/key)
                                       :heading (html
                                                  [:span (str project-name " has no ")
                                                   (empty-state/important "code signing keys")
                                                   " yet"])
                                       :subheading "Apple Code Signing requires a valid Code Signing Identity (p12) file."
                                       :action (button/button {:on-click #(om/set-state! owner :show-modal? true)
                                                               :kind :primary}
                                                              "Upload Key")}))
           (when show-modal?
             (om/build p12-upload-modal {:close-fn #(om/set-state! owner :show-modal? false)
                                         :error-message error-message
                                         :project-name project-name
                                         :vcs-type vcs-type}))]])))))

(defn code-signing-profiles [{:keys [project-data error-message]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})
    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [{:keys [project osx-keys osx-profiles]} project-data
            project-name (vcs-url/project-name (:vcs_url project))
            vcs-type (project-model/vcs-type project)]
        (html
         [:section.code-signing-page {:data-component `code-signing-profiles}
          [:article
           [:div.header
            [:div.title "Provisioning Profiles"]
            (button/button
              {:on-click #(om/set-state! owner :show-modal? true)
               :kind :primary}
              "Upload Profile")]
           [:hr.divider]
           [:div.info "The following provisioning profiles will be copied to the system location when your build
                      starts. This is to enable Xcode to sign your application. A provisioning profile should not
                      usually be required for running tests, but will be required for ad-hoc, TestFlight, and
                      App Store builds. See our "
            [:a
             {:href "https://circleci.com/docs/ios-code-signing/"}
             "code-signing documentation"]
            " for more details."]
           (if-not (empty? osx-profiles)
             (om/build provisioning-profiles-table {:rows (->> osx-profiles
                                                              (map (partial merge {:project-name project-name
                                                                                   :vcs-type vcs-type})))})
             (empty-state/empty-state {:icon (icon/key)
                                       :heading (html
                                                  [:span (str project-name " has no ")
                                                   (empty-state/important "provisioning profiles")
                                                   " yet"])
                                       :subheading "Apple Code Signing requires a valid provisioning profile (mobileprovision)."
                                       :action (button/button {:on-click #(om/set-state! owner :show-modal? true)
                                                               :kind :primary}
                                                              "Upload Profile")}))
           (when show-modal?
             (om/build provisioning-profile-upload-modal
                       {:close-fn #(om/set-state! owner :show-modal? false)
                        :error-message error-message
                        :project-name project-name
                        :vcs-type vcs-type}))]])))))

(defn code-signing [data owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         (om/build code-signing-keys data)
         (om/build code-signing-profiles data)]))))

(defn project-settings [data owner]
  (reify
    om/IRender
    (render [_]
      (let [project-data (get-in data state/project-data-path)
            projects (get-in data state/org-projects-path)
            user (:current-user data)
            subpage (-> data :navigation-data :subpage)
            error-message (get-in data state/error-message-path)
            proj-vcs-url (get-in project-data [:project :vcs_url])]
        (html
         (if-not proj-vcs-url ; wait for project-settings to load
           [:div.empty-placeholder (spinner)]
           [:div#project-settings
            ; Temporarly disable top level error messsage for the set of subpages while we
            ; transition them. Each subpage will eventually handle their own error messages.
            (when-not (contains? #{:code-signing :jira-integration} subpage)
              (om/build common/flashes error-message))
            [:div#subpage
             (case subpage
               :build-environment (om/build build-environment project-data)
               :parallel-builds (om/build parallel-builds data)
               :env-vars (om/build env-vars {:project-data project-data
                                             :projects projects
                                             :org-admin? (->> user
                                                              :organizations
                                                              (filter (fn [org]
                                                                        (= (:login org)
                                                                           (vcs-url/org-name proj-vcs-url))))
                                                              first
                                                              :admin)})
               :advanced-settings (om/build advance project-data)
               :clear-caches (if (or (feature/enabled? :project-cache-clear-buttons)
                                     (config/enterprise?))
                               (om/build clear-caches project-data)
                               (om/build overview project-data))
               :setup (om/build dependencies project-data)
               :tests (om/build tests project-data)
               :hooks (om/build notifications project-data)
               :webhooks (om/build webhooks project-data)
               :badges (om/build status-badges project-data)
               :ssh (om/build ssh-keys project-data)
               :checkout (om/build checkout-ssh-keys {:project-data project-data :user user})
               :api (om/build api-tokens project-data)
               :heroku (om/build heroku {:project-data project-data :user user})
               :deployment (om/build other-deployment project-data)
               :aws (om/build aws project-data)
               :jira-integration (om/build jira-integration project-data)
               :aws-codedeploy (om/build aws-codedeploy project-data)
               :code-signing (om/build code-signing {:project-data project-data :error-message error-message})
               (om/build overview project-data))]]))))))
