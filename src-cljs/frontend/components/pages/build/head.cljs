(ns frontend.components.pages.build.head
  (:require [cljs.core.async :as async :refer [chan]]
            [clojure.string :as string]
            [frontend.async :refer [raise!]]
            [frontend.components.build-head :as old-build-head]
            [frontend.components.builds-table :as builds-table]
            [frontend.components.common :as common]
            [frontend.components.pages.build.head.trigger :as trigger]
            [frontend.components.pieces.card :as card]
            [frontend.components.pieces.icon :as icon]
            [frontend.components.pieces.popover :as popover]
            [frontend.components.pieces.status :as status]
            [frontend.config :refer [enterprise? github-endpoint]]
            [frontend.datetime :as datetime]
            [frontend.models.build :as build-model]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.timer :as timer]
            [frontend.utils :as utils :refer-macros [component defrender element html]]
            [frontend.utils.bitbucket :as bb-utils]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs :as vcs]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.string :as gstring]
            [frontend.state :as state]
            [om.core :as om :include-macros true])
  (:require-macros [devcards.core :as dc :refer [defcard-om]]))

(defn- summary-item [label value]
  (component
    (html
     [:.summary-item
      (when label
        [:span.summary-label label])
      value])))

(defn- linkify [text]
  (let [url-pattern #"(?im)(\b(https?|ftp)://[-A-Za-z0-9+@#/%?=~_|!:,.;]*[-A-Za-z0-9+@#/%=~_|])"
        pseudo-url-pattern #"(?im)(^|[^/])(www\.[\S]+(\b|$))"]
    (-> text
        ;; TODO: switch to clojure.string/replace when they fix
        ;; http://dev.clojure.org/jira/browse/CLJS-485...
        (.replace (js/RegExp. (.-source url-pattern) "gim")
                  "<a href=\"$1\" target=\"_blank\">$1</a>")
        (.replace (js/RegExp. (.-source pseudo-url-pattern) "gim")
                  "$1<a href=\"http://$2\" target=\"_blank\">$2</a>"))))

(defn- replace-issue [text link endpoint project-name]
  (string/replace text
                  #"(^|\s)#(\d+)\b"
                  (gstring/format link endpoint project-name)))

(defn- maybe-project-linkify [text vcs-type project-name]
  (cond
    (and project-name (= "bitbucket" vcs-type) (re-find #"pull request #\d+" text))
    (replace-issue text
                   "$1<a href='%s/%s/pull-requests/$2' target='_blank'>pull request #$2</a>"
                   (bb-utils/http-endpoint)
                   project-name)
    project-name
    (replace-issue text
                   "$1<a href='%s/%s/issues/$2' target='_blank'>#$2</a>"
                   (case vcs-type
                     "github" (gh-utils/http-endpoint)
                     "bitbucket" (bb-utils/http-endpoint))
                   project-name)

    :else text))

(defn- commit-line [{:keys [author_name build subject body commit_url commit] :as commit-details} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when (seq body)
        (utils/tooltip (str "#commit-line-tooltip-hack-" commit)
                       {:placement "bottom"
                        :animation false
                        :viewport ".build-commits-container"})))
    om/IRender
    (render [_]
      (component
        (html
         [:div
          [:span.metadata-item
           (if-not (:author_email commit-details)
             [:span
              (build-model/author commit-details)]
             [:a {:href (str "mailto:" (:author_email commit-details))}
              (build-model/author commit-details)])]
          (when (build-model/author-isnt-committer commit-details)
            [:span.metadata-item
             (if-not (:committer_email commit-details)
               [:span
                (build-model/committer commit-details)]
               [:a {:href (str "mailto:" (:committer_email commit-details))}
                (build-model/committer commit-details)])])

          [:i.octicon.octicon-git-commit]
          [:a.metadata-item.sha-one {:href commit_url
                                     :title commit
                                     :on-click #((om/get-shared owner :track-event) {:event-type :revision-link-clicked})}
           (subs commit 0 7)]
          [:span.commit-message
           {:title body
            :id (str "commit-line-tooltip-hack-" commit)
            :dangerouslySetInnerHTML {:__html (let [vcs-url (:vcs_url build)]
                                                (-> subject
                                                    (utils/convert-emoji)
                                                    (gstring/htmlEscape)
                                                    (linkify)
                                                    (maybe-project-linkify (vcs-url/vcs-type vcs-url)
                                                                           (vcs-url/project-name vcs-url))))}}]])))))

(def ^:private initial-build-commits-count 3)

(defn- build-commits [{:keys [build show-all-commits?]} owner]
  (reify
    om/IRender
    (render [_]
      (let [build-id (build-model/id build)
            commits (:all_commit_details build)
            [top-commits bottom-commits] (->> commits
                                              (map #(assoc % :build build))
                                              (split-at initial-build-commits-count))]
        (component
          (html
           ;; .build-commits-container class is used for utils/tooltip in commit-line.
           [:.build-commits-container
            (when (:subject build)
              [:.build-commits-list
               (if-not (seq commits)
                 (om/build commit-line {:build build
                                        :subject (:subject build)
                                        :body (:body build)
                                        :commit_url (build-model/commit-url build)
                                        :commit (:vcs_revision build)})
                 (list
                  (om/build-all commit-line top-commits {:key :commit})
                  (when (< initial-build-commits-count (count commits))
                    (list
                     [:hr]
                     [:a {:class "chevron-label"
                          :role "button"
                          :on-click #(raise! owner [:show-all-commits-toggled])}
                      [:i.fa.rotating-chevron {:class (when show-all-commits? "expanded")}]
                      (if show-all-commits?
                        "Less"
                        "More")]))
                  (when show-all-commits?
                    (om/build-all commit-line bottom-commits {:key :commit}))))])]))))))

(defrender pull-requests [prs owner]
  (html
   [:span
    (interpose
     ", "
     (for [url (map :url prs)
           ;; WORKAROUND: We have/had a bug where a PR URL would be reported as nil.
           ;; When that happens, this code blows up the page. To work around that,
           ;; we just skip the PR if its URL is nil.
           :when url]
       [:a {:href url
            :on-click #((om/get-shared owner :track-event)
                        {:event-type :pr-link-clicked})}
        "#"
        (gh-utils/pull-request-number url)]))]))

(defn- summary-header [{{:keys [stop_time start_time parallel usage_queued_at picard
                                pull_requests status canceler vcs_url] :as build} :build
                        :keys [project plan]} owner]
  (reify
    om/IRender
    (render [_]
      (component
        (html
         [:div
          (summary-item nil (status/build-badge (build-model/build-status build)))

          (if-not stop_time
            (when start_time
              (summary-item
               "Started:"
               (html
                [:span {:title (datetime/full-datetime start_time)}
                 (om/build common/updating-duration {:start start_time})
                 " ago"])))

            (summary-item
             "Finished:"
             (list
              (html
               [:span {:title (datetime/full-datetime stop_time)}
                (om/build common/updating-duration
                          {:start stop_time}
                          {:opts {:formatter datetime/time-ago-abbreviated}})
                " ago"])
              (str " (" (build-model/duration build) ")"))))

          (when (build-model/running? build)
            (summary-item
             "Estimated:"
             (datetime/as-duration (:build_time_millis (:previous_successful_build build)))))

          (when-let [build-number (:build_num (:previous build))]
            (summary-item
             "Previous:"
             (html
              [:a {:href (routes/v1-build-path (vcs-url/vcs-type vcs_url)
                                               (vcs-url/org-name vcs_url)
                                               (vcs-url/repo-name vcs_url)
                                               nil build-number)}
               build-number])))

          (when (project-model/parallel-available? project)
            (summary-item
             "Parallelism:"
             (html
               [:a
                {:title (str "This build used " parallel " containers. Click here to change parallelism for future builds.")
                 :on-click #((om/get-shared owner :track-event) {:event-type :parallelism-clicked
                                                                 :properties {:repo (project-model/repo-name project)
                                                                              :org (project-model/org-name project)}})
                 :href (routes/v1-project-settings-path {:vcs_type (-> build build-model/vcs-url vcs-url/vcs-type vcs/->short-vcs)
                                                         :org (vcs-url/org-name (build-model/vcs-url build))
                                                         :repo (vcs-url/repo-name (build-model/vcs-url build))
                                                         :_fragment "parallel-builds"})}
                parallel "x"
                (when (not (enterprise?))
                  (str " out of " (project-model/buildable-parallelism plan project) "x"))])))

          (when usage_queued_at
            (summary-item
             "Queued:"
             (if (< 0 (build-model/run-queued-time build))
               (list
                (om/build common/updating-duration {:start (:usage_queued_at build)
                                                    :stop (or (:queued_at build) (:stop_time build))})
                " waiting + "
                (om/build common/updating-duration {:start (:queued_at build)
                                                    :stop (or (:start_time build) (:stop_time build))})
                " in queue")

               (list
                (om/build common/updating-duration {:start (:usage_queued_at build)
                                                    :stop (or (:queued_at build) (:stop_time build))})
                " waiting for builds to finish"))))

          (when-let [resource_class (:resource_class picard)]
            (summary-item
              [:span "Resources:"
               [:span.resource-class
                (popover/tooltip {:body (html [:span "Your job's resource is defined through your configuration."
                                               (let [href "https://circleci.com/docs/2.0/configuration-reference/#jobs"]
                                                 [:div [:a {:href href
                                                            :target "_blank"
                                                            :on-click #((om/get-shared owner :track-event) {:event-type :resource-class-docs-clicked
                                                                                                            :properties {:href href}})}
                                                        "Read more in our docs →"]])])
                                  :placement :bottom}
                                 [:i.fa.fa-question-circle])]]
              [:span
               (gstring/format "%sCPU/%sMB"
                               (:cpu resource_class)
                               (:ram resource_class))]))
          (when-let [{:keys [workflow_id workflow_name]} (:workflows build)]
            (summary-item
             [:span "Workflow:"]
             [:span
              [:a {:href (routes/v1-run-path workflow_id)}
               workflow_name]]))

          [:.right-side
           (summary-item
            "Triggered by:"
            (trigger/description build))

           (when (and (= "canceled" status) canceler)
             (summary-item
              "Canceled by:"
              (let [{:keys [type name login]} canceler]
                (html
                 [:a {:href (case type
                              "github" (str (github-endpoint) "/" login)
                              "bitbucket" (bb-utils/user-profile-url login)
                              nil)}
                  (if (not-empty name) name login)]))))

           (when (build-model/has-pull-requests? build)
             (summary-item
              (str "PR" (when (< 1 (count pull_requests)) "s") ":")
              (om/build pull-requests pull_requests)))]])))))

(defn- build-head-content [{:keys [build-data project-data] :as data} owner]
  (reify
    om/IRender
    (render [_]
      (component
        (let [{:keys [all_commit_details all_commit_details_truncated] :as build} (:build build-data)
              {:keys [project plan]} project-data]
          (html
           [:div
            [:.summary-header
             (om/build summary-header {:build build
                                       :project project
                                       :plan plan})]

            (card/basic
             (element :commits
               (html
                [:div
                 [:.heading
                  (let [n (count all_commit_details)]
                    (if all_commit_details_truncated
                      (gstring/format "Last %d Commits" n)
                      (gstring/format "Commits (%d)" n)))]
                 (om/build build-commits build-data)])))]))))))

(defrender build-link [{:keys [job workflow]} owner]
  (html
   (if (get-in job [:data :build :build_num])
     (let [build (get-in job [:data :build])
           {vcs-url :vcs_url
            build-number :build_num} build
           {workflow-id :id} workflow
           url (routes/v1-build-path (vcs-url/vcs-type vcs-url) (vcs-url/org-name vcs-url) (vcs-url/repo-name vcs-url) workflow-id build-number)]
       [:a {:title (str (:username build) "/" (:reponame build) " #" (:build_num build))
            :href url
            :on-click #((om/get-shared owner :track-event) {:event-type :build-link-clicked
                                                            :properties {:org (vcs-url/org-name (:vcs_url build))
                                                                         :repo (:reponame build)
                                                                         :vcs-type (vcs-url/vcs-type (:vcs_url build))}})}
        (:name job)
        " #"
        (:build_num build)])
     [:div "not run yet"])))

(defn build-head [data owner]
  (reify
    om/IRender
    (render [_]
      (component
        (html
         [:div
          (om/build build-head-content (select-keys data [:build-data :project-data]))
          [:div.build-sub-head
           (om/build old-build-head/build-sub-head data)]])))))

(dc/do
  (def ^:private sample-data
    {:build-data {:build {:vcs_url "https://github.com/acme/anvil"
                          :previous_successful_build {:build_time_millis 342410}
                          :previous {:build_num 3973}
                          :parallel 5
                          :usage_queued_at "2017-02-23T18:10:20.541Z"
                          :queued_at "2017-02-23T18:13:20.541Z"
                          :pull_requests [{:url "https://github.com/acme/anvil/pull/1726"}]
                          :why "retry"
                          :retry_of 3973
                          :user {:vcs_type "github"
                                 :name "Wile E. Coyote"
                                 :login "wecoyote"}

                          :subject "Merge pull request #11 from acme/increased-mass"

                          :all_commit_details
                          [{:commit_url
                            "https://github.com/Peeja/a-test/commit/cfbb2c8e5734c2dee7360568c3d82982feb966f9"
                            :committer_name "Wile E. Coyote"
                            :committer_email "wecoyote@gmail.com"
                            :author_date "2016-04-28T15:40:26-04:00"
                            :author_login "wecoyote"
                            :committer_login "wecoyote"
                            :commit "cfbb2c8e5734c2dee7360568c3d82982feb966f9"
                            :author_name "Wile E. Coyote"
                            :author_email "wecoyote@gmail.com"
                            :committer_date "2016-04-28T15:40:26-04:00"
                            :branch "master"
                            :body ""
                            :subject "Increase anvil mass"}
                           {:commit_url
                            "https://github.com/Peeja/a-test/commit/cfbb2c8e5734c2dee7360568c3d82982feb966fa"
                            :committer_name "Wile E. Coyote"
                            :committer_email "wecoyote@gmail.com"
                            :author_date "2016-04-28T15:41:26-04:00"
                            :author_login "wecoyote"
                            :committer_login "wecoyote"
                            :commit "cfbb2c8e5734c2dee7360568c3d82982feb966fa"
                            :author_name "Wile E. Coyote"
                            :author_email "wecoyote@gmail.com"
                            :committer_date "2016-04-28T15:41:26-04:00"
                            :branch "master"
                            :body ""
                            :subject "Increase anvil mass more"}
                           {:commit_url
                            "https://github.com/Peeja/a-test/commit/cfbb2c8e5734c2dee7360568c3d82982feb966fb"
                            :committer_name "Wile E. Coyote"
                            :committer_email "wecoyote@gmail.com"
                            :author_date "2016-04-28T15:42:26-04:00"
                            :author_login "wecoyote"
                            :committer_login "wecoyote"
                            :commit "cfbb2c8e5734c2dee7360568c3d82982feb966fb"
                            :author_name "Wile E. Coyote"
                            :author_email "wecoyote@gmail.com"
                            :committer_date "2016-04-28T15:42:26-04:00"
                            :branch "master"
                            :body ""
                            :subject "Increase anvil mass even more"}
                           {:commit_url
                            "https://github.com/Peeja/a-test/commit/cf16ef42db90668da03d6c2182f0a4b5ab1c71ca"
                            :committer_name "Wile E. Coyote"
                            :committer_email "wecoyote@gmail.com"
                            :author_date "2016-04-28T15:42:44-04:00"
                            :author_login "wecoyote"
                            :committer_login "wecoyote"
                            :commit "cf16ef42db90668da03d6c2182f0a4b5ab1c71ca"
                            :author_name "Wile E. Coyote"
                            :author_email "wecoyote@gmail.com"
                            :committer_date "2016-04-28T15:42:44-04:00"
                            :branch "master"
                            :body "Fork PR"
                            :subject "Merge pull request #11 from acme/increased-mass"}]}}
     :project-data {:project {:oss false}
                    :plan {:paid true
                           :containers 10
                           :max_parallelism 10}}})

  (def ^:private card-opts
    {:shared {:comms {:controls (chan)}
              :track-event (constantly nil)
              :timer-atom (timer/initialize)}})

  (defcard-om not-running
    build-head-content
    (update-in sample-data [:build-data :build] assoc
               :status "not_running"
               :queued_at nil)
    card-opts)

  (defcard-om queued
    build-head-content
    (update-in sample-data [:build-data :build] assoc
               :status "queued")
    card-opts)

  (defcard-om running
    build-head-content
    (update-in sample-data [:build-data :build] assoc
               :status "running"
               :start_time "2017-02-23T18:14:20.541Z")
    card-opts)

  (defcard-om success
    build-head-content
    (update-in sample-data [:build-data :build] assoc
               :status "success"
               :start_time "2017-02-23T18:14:20.541Z"
               :stop_time "2017-02-23uT18:32:20.541Z")
    card-opts)

  (defcard-om canceled
    build-head-content
    (update-in sample-data [:build-data :build] assoc
               :status "canceled"
               :start_time "2017-02-23T18:14:20.541Z"
               :stop_time "2017-02-23uT18:32:20.541Z"
               :canceler {:type "github"
                          :name "Road Runner"
                          :login "rrunner"})
    card-opts))
