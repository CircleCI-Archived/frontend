(ns frontend.components.builds-table
  (:require [cemerick.url :as url]
            [frontend.async :refer [raise!]]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.components.pieces.icon :as icon]
            [frontend.components.pieces.popover :as popover]
            [frontend.components.pieces.status :as status]
            [frontend.datetime :as datetime]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.utils :as utils :refer-macros [defrender html]]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]))

(defn dashboard-icon [name]
  [:img.dashboard-icon {:src (utils/cdn-path (str "/img/inner/icons/" name ".svg"))}])

(defn avatar [user & {:keys [size trigger] :or {size 40} :as opts}]
  (if-let [avatar-url (-> user :avatar_url)]
    [:img.dashboard-icon
     ;; Adding `&s=N` to the avatar URL returns an NxN version of the
     ;; avatar (except, for some reason, for default avatars, which are
     ;; always returned full-size, but they're sized with CSS anyhow).
     {:src (-> avatar-url url/url (assoc-in [:query "s"] size) str)}]
    (if (= trigger "api")
      (dashboard-icon "Bot-Icon")
      (dashboard-icon "Default-Avatar"))))

(defn build-action [{:keys [text loading-text icon-name icon-class on-click]}]
  [:div.build-action
   (forms/managed-button
     [:button
      {:data-loading-text loading-text
       :on-click on-click}
      (cond
        icon-name [:img.button-icon {:src (common/icon-path icon-name)}]
        icon-class [:i.button-icon {:class icon-class}])
      [:span.button-text text]])])

(defrender pull-requests [{:keys [build]} owner]
  (html
   (when-let [urls (seq (map :url (:pull_requests build)))]
     [:span.metadata-item.pull-requests {:title "Pull Requests"}
      (icon/git-pull-request)
      (interpose
       ", "
       (for [url urls
             ;; WORKAROUND: We have/had a bug where a PR URL would be reported as nil.
             ;; When that happens, this code blows up the page. To work around that,
             ;; we just skip the PR if its URL is nil.
             :when url]
         [:a {:href url
              :on-click #((om/get-shared owner :track-event) {:event-type :pr-link-clicked
                                                              :properties {:repo (:reponame build)
                                                                           :org (:username build)}})}
          "#"
          (gh-utils/pull-request-number url)]))])))

(defrender commits [{:keys [build]} owner]
  (html
   (when (:vcs_revision build)
     [:span.metadata-item.revision
      [:i.octicon.octicon-git-commit]
      [:a {:title (build-model/github-revision build)
           :href (build-model/commit-url build)
           :on-click #((om/get-shared owner :track-event) {:event-type :revision-link-clicked
                                                           :properties {:repo (:reponame build)
                                                                        :org (:username build)}})}
       (build-model/github-revision build)]])))

(defn- platform-link
  [{:keys [link links-to body-text owner]}]
  [:span body-text
   [:div
    [:a {:href link
         :target "_blank"
         :on-click #((om/get-shared owner :track-event) {:event-type :platform-link-clicked
                                                         :properties {:component "builds-table-popover"
                                                                      :link link
                                                                      :links-to links-to}})}
     "Learn more →"]]])

(defn build-engine-popover
  [platform owner]
  (popover/popover {:title nil
                    :body (if (= "1.0" platform)
                            (platform-link {:body-text "2.0 is coming soon!\n"
                                            :link "https://circleci.com/beta-access/"
                                            :links-to "beta-access"
                                            :owner owner})
                            (platform-link {:body-text "This build ran on 2.0.\n"
                                            :link "https://circleci.com/docs/2.0/"
                                            :links-to "beta-docs"
                                            :owner owner}))
                    :placement :left
                    :trigger-mode :click
                    :on-show #((om/get-shared owner :track-event) {:event-type :platform-number-popover-impression
                                                                   :properties {:component "builds-table"
                                                                                :platform-number platform}})}
                   [:span.platform platform]))

(defn build-row [{:keys [build project]} owner {:keys [show-actions? show-branch? show-project?]}]
  (let [url (build-model/path-for (select-keys build [:vcs_url]) build)
        build-args (merge (build-model/build-args build) {:component "build-row" :no-cache? false})
        build-status (build-model/build-status build)
        should-show-cancel? (and (project-model/can-trigger-builds? project)
                                 (build-model/can-cancel? build))
        should-show-rebuild? (and (project-model/can-trigger-builds? project)
                                  (#{"timedout" "failed"} (:outcome build)))
        platform (:platform build)
        workflow-data (:workflows build)]
    [:div.build {:class (-> build build-model/build-status build-model/status-class name)}
     [:div.status-area
      [:a {:href url
           :on-click #((om/get-shared owner :track-event) {:event-type :build-status-clicked
                                                           :properties {:build-status build-status}})}
       (status/build-badge build-status)]

      ;; Actions should be mutually exclusive. Just in case they
      ;; aren't, use a cond so it doesn't try to render both in the
      ;; same place
      (cond
        should-show-cancel?
        (build-action
         {:text "cancel"
          :loading-text "Cancelling..."
          :icon-name "Status-Canceled"
          :on-click #(do
                       (raise! owner [:cancel-build-clicked build-args])
                       ((om/get-shared owner :track-event) {:event-type :cancel-build-clicked}))})

        should-show-rebuild?
        (build-action
         {:text "rebuild"
          :loading-text "Rebuilding..."
          :icon-name "Rebuild"
          :on-click #(raise! owner [:retry-build-clicked build-args])})
        :else nil)]

     [:div.build-info
      [:div.build-info-container
       [:div.build-info-header
        [:div.contextual-identifier
         [:a {:title (str (:username build) "/" (:reponame build) " #" (:build_num build))
              :href url
              :on-click #((om/get-shared owner :track-event) {:event-type :build-link-clicked
                                                              :properties {:org (vcs-url/org-name (:vcs_url build))
                                                                           :repo (:reponame build)
                                                                           :vcs-type (vcs-url/vcs-type (:vcs_url build))}})}

          (when show-project?
            (str (:username build) " / " (:reponame build) " "))

          (when (and show-project? show-branch?) " / ")
          (when show-branch?
            (-> build build-model/vcs-ref-name))
          " #"
          (:build_num build)]]]
       (when workflow-data
         [:div.workflow-info
          [:div.workflows-icon (icon/workflows)]
          [:a {:href (routes/v1-run-path (:workflow_id workflow-data))}
           (:workflow_name workflow-data)]])
       [:div.recent-commit-msg
        (let [pusher-name (build-model/ui-user build)
              trigger (:why build)]
          [:div.recent-user
           {:title (if (= "api" trigger)
                     "API"
                     pusher-name)
            :data-toggle "tooltip"
            :data-placement "right"}
           (avatar (:user build) :trigger trigger)])
        [:span.recent-log
         {:title (:body build)}
         (utils/convert-emoji (:subject build))]]]
      [:div.build-engine.screen-md-down (build-engine-popover platform owner)]]

     [:div.metadata
      [:div.metadata-row.timing
       (if (or (not (:start_time build))
               (= "not_run" (:status build)))
         (list
          [:span.metadata-item.recent-time.start-time
           {:title "Started: not started"}
           [:i.material-icons "today"]
           "–"]
          [:span.metadata-item.recent-time.duration
           {:title "Duration: not run"}
           [:i.material-icons "timer"]
           "–"])
         (list [:span.metadata-item.recent-time.start-time
                {:title (str "Started: " (datetime/full-datetime (js/Date.parse (:start_time build))))}
                [:i.material-icons "today"]
                (om/build common/updating-duration {:start (:start_time build)} {:opts {:formatter datetime/time-ago-abbreviated}})
                [:span.ago " ago"]]
               [:span.metadata-item.recent-time.duration
                {:title (str "Duration: " (build-model/duration build))}
                [:i.material-icons "timer"]
                (om/build common/updating-duration {:start (:start_time build)
                                                    :stop (:stop_time build)})]))]
      [:div.metadata-row.pull-revision
       (om/build pull-requests {:build build})
       (om/build commits {:build build})]]
     [:div.build-engine.screen-md-up (build-engine-popover platform owner)]]))

(defn job-waiting-badge
  "badge for waiting job."
  [job]
  (status/build-badge :build-status/not-running))

(defn build-job-row-status [job build url project owner]
  (let [build-args (merge (build-model/build-args build) {:component "build-row" :no-cache? false})
        build-status (build-model/build-status build)
        should-show-cancel? (and project
                                 (project-model/can-trigger-builds? project)
                                 (build-model/can-cancel? build))]
    (if url
      [:div.status-area
       [:a {:href url
            :on-click #((om/get-shared owner :track-event) {:event-type :build-status-clicked
                                                            :properties {:build-status build-status}})}
        (status/build-badge build-status)]

       ;; Actions should be mutually exclusive. Just in case they
       ;; aren't, use a cond so it doesn't try to render both in the
       ;; same place
       (cond
         should-show-cancel?
         (build-action
          {:text "cancel"
           :loading-text "Cancelling..."
           :icon-name "Status-Canceled"
           :on-click #(do
                        (raise! owner [:cancel-build-clicked build-args])
                        ((om/get-shared owner :track-event) {:event-type :cancel-build-clicked}))})
         :else nil)]
      [:div.status-area
       (job-waiting-badge job)])))

(defn build-job-row [{:keys [workflow-id previous-job job next-job project]} owner]
  (let [{build-number :build_num
         vcs-url :vcs_url
         :as build} (get-in job [:data :build])]
    (let [url (and build
                   (routes/v1-build-path (vcs-url/vcs-type vcs-url) (vcs-url/org-name vcs-url) (vcs-url/repo-name vcs-url) workflow-id build-number))
          {previous-vcs-url :vcs_url
           previous-build-number :build_num
           :as previous-build} (get-in previous-job [:data :build])
          previous-url (and previous-vcs-url
                            (routes/v1-build-path (vcs-url/vcs-type previous-vcs-url) (vcs-url/org-name previous-vcs-url) (vcs-url/repo-name previous-vcs-url) workflow-id previous-build-number))]
      [:div.build {:class (-> build build-model/build-status build-model/status-class name)}
       (build-job-row-status job build url project owner)
       [:div.build-info
        [:div.build-info-header
         [:div.contextual-identifier
          (if url
            [:a {:title (str (:username build) "/" (:reponame build) " #" build-number)
                 :href url
                 :on-click #((om/get-shared owner :track-event) {:event-type :build-link-clicked
                                                                 :properties {:org (vcs-url/org-name (:vcs_url build))
                                                                              :repo (:reponame build)
                                                                              :vcs-type (vcs-url/vcs-type (:vcs_url build))}})}
             (:name job) " #"
             build-number]
            [:div (:name job)])]]
        [:div.recent-commit-msg
         (if build
           (let [pusher-name (build-model/ui-user build)
                 trigger (:why build)]
             [:div.recent-user
              {:title (if (= "api" trigger)
                        "API"
                        pusher-name)
               :data-toggle "tooltip"
               :data-placement "right"}
              (avatar (:user build) :trigger trigger)])
           [:div.recent-user
            [:i.material-icons "adjust"]])
         (cond previous-url
               [:span.recent-log
                [:a {:title (str (:username previous-build) "/" (:reponame previous-build) " #" previous-build-number)
                     :href previous-url
                     :on-click #((om/get-shared owner :track-event) {:event-type :build-link-clicked
                                                                     :properties {:org (vcs-url/org-name (:vcs_url previous-build))
                                                                                  :repo (:reponame previous-build)
                                                                                  :vcs-type (vcs-url/vcs-type (:vcs_url previous-build))}})}
                 (:name previous-job) " #"
                 previous-build-number]]
               build
               [:span.recent-log
                {:title (:body build)}
                (utils/convert-emoji (:subject build))])]]

       [:div.metadata
        [:div.metadata-row.timing
         (if (or (not (:start_time build))
                 (= "not_run" (:status build)))
           (list
            [:div.metadata-item.recent-time.start-time
             {:title "Started: not started"}
             [:i.material-icons "today"]
             "–"]
            [:div.metadata-item.recent-time.duration
             {:title "Duration: not run"}
             [:i.material-icons "timer"]
             "–"])
           (list [:div.metadata-item.recent-time.start-time
                  {:title (str "Started: " (datetime/full-datetime (js/Date.parse (:start_time build))))}
                  [:i.material-icons "today"]
                  (om/build common/updating-duration {:start (:start_time build)} {:opts {:formatter datetime/time-ago-abbreviated}})
                  [:span.ago " ago"]]
                 [:div.metadata-item.recent-time.duration
                  {:title (str "Duration: " (build-model/duration build))}
                  [:i.material-icons "timer"]
                  (om/build common/updating-duration {:start (:start_time build)
                                                      :stop (:stop_time build)})]))]
        [:div.metadata-row.pull-revision
         (when build
           (list (om/build pull-requests {:build build})
                 (om/build commits {:build build})))]]])))

(defn- next-this-previous [items]
  (let [previous-items (concat (list nil) items)
        next-items (concat (drop 1 items) (repeat nil))]
    (map vector previous-items items next-items)))

(defn builds-table [data owner {:keys [show-actions? show-branch? show-project?]
                                :or {show-branch? true
                                     show-project? true}}]
  (let [{:keys [builds projects]} data
        projects-by-vcs_url (into {}
                                  (map (juxt :vcs_url identity) projects))]
    (reify
      om/IDisplayName (display-name [_] "Builds Table V2")
      om/IRender
      (render [_]
        (html
         [:div.container-fluid
          (map #(build-row {:build %
                            :project (get projects-by-vcs_url (:vcs_url %))}
                           owner {:show-actions? show-actions?
                                  :show-branch? show-branch?
                                  :show-project? show-project?})
               builds)])))))
