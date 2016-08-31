(ns frontend.components.build
  (:require [clojure.string :as string]
            [frontend.async :refer [raise!]]
            [frontend.routes :as routes]
            [frontend.datetime :as datetime]
            [frontend.models.build :as build-model]
            [frontend.models.container :as container-model]
            [frontend.models.feature :as feature]
            [frontend.models.plan :as plan-model]
            [frontend.models.project :as project-model]
            [frontend.components.build-head :as build-head]
            [frontend.components.invites :as invites]
            [frontend.components.build-steps :as build-steps]
            [frontend.components.common :as common]
            [frontend.components.project.common :as project-common]
            [frontend.components.svg :refer [svg]]
            [frontend.config :refer [enterprise?]]
            [frontend.scroll :as scroll]
            [frontend.state :as state]
            [frontend.config :as config]
            [frontend.timer :as timer]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.html :refer [open-ext]]
            [frontend.utils.seq :refer [find-index]]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.string :as gstring]
            [om.core :as om :include-macros true])
    (:require-macros [frontend.utils :refer [html defrender]]))

(defn infrastructure-fail-message [owner fail-reason]
  (if (enterprise?)
    [:div
     "Looks like you may have encountered a bug in the build infrastructure. "
     "Your build should have been automatically retried.  If the problem persists, please "
     (common/contact-us-inner owner)
     ", so CircleCI can investigate."]

    (cond
      (= fail-reason "exit-code-65-bug")
      [:div "This build failed as an \"Exit Code 65\" bug. "
       "You will not be charged for this build and it will be retried up to 3 times."
       "To follow progress regarding the root cause of this error, please follow "
       [:a {:href "https://discuss.circleci.com/t/xcode-exit-code-65/4284/9"
            :target "_blank"}
        "this Discuss post."]]

      :else
      [:div
       "Looks like we had a bug in our infrastructure, or that of our providers (generally "
       [:a {:href "https://status.github.com/"} "GitHub"]
       " or "
       [:a {:href "http://status.aws.amazon.com/"} "AWS"]
       ") We should have automatically retried this build. We've been alerted of"
       " the issue and are almost certainly looking into it, please "
       (common/contact-us-inner owner)
       " if you're interested in the cause or if the problem persists."])))

(defn report-error [{:keys [build show-premium-content?]} owner]
  (let [build-id (build-model/id build)
        build-url (:build_url build)
        fail-reason (:fail_reason build)]
    (when (:failed build)
      [:div.alert.alert-danger.iconified
       [:div [:img.alert-icon {:src (common/icon-path "Info-Error")}]]
       (if (:infrastructure_fail build)
         (infrastructure-fail-message owner fail-reason)
         [:div.alert-wrap
          "If you continue to get stuck, we suggest checking out our "
          [:a (open-ext {:href "https://circleci.com/docs/troubleshooting/"})
           "docs"]
          " and/or our "
          [:a {:href "https://discuss.circleci.com/"}
           "community site"]
          "."
          (let [support-link [:a (common/contact-support-a-info owner :tags [:report-build-clicked {:build-url build-url}])
                              "contact engineering support"]]
            (cond
              (config/enterprise?) [:span "As an enterprise customer, you may also "
                                    support-link
                                    " to ask for help. Thanks!"]
              show-premium-content? [:span
                                     " As this project builds under a paid plan, you may also "
                                     support-link
                                     " to ask for help. Thanks!"]
              :else [:span " Upgrading to a paid plan unlocks access to CircleCI engineering support, faster builds, and advanced features. Thanks!"]))])])))

(defn sticky [{:keys [wrapper-class content-class content]} owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [stick]}]
      (let [wrapper-style (when stick
                            {:height (:height stick)})
            content-style (when stick
                            {:position :fixed
                             :top (:top stick)
                             :left (:left stick)
                             :width (:width stick)
                             :background-color "#f5f5f5"})]
        (html [:div {:ref "wrapper" :class wrapper-class :style wrapper-style}
               [:div {:ref "content" :class content-class :style content-style}
                content]])))

    om/IDidMount
    (did-mount [_]
      (let [scroll-window (utils/sel1 ".main-body")]
        (scroll/register owner
                         #(let [scroll-rect (.getBoundingClientRect scroll-window)
                                wrapper (om/get-node owner "wrapper")
                                content (om/get-node owner "content")
                                wrapper-rect (.getBoundingClientRect wrapper)
                                content-height (.-height (.getBoundingClientRect content))
                                stick? (<= (.-top wrapper-rect) (.-top scroll-rect))]
                            (om/set-state! owner :stick
                                           (when stick?
                                             {:top (.-top scroll-rect)
                                              :left (.-left scroll-rect)
                                              :width (.-width scroll-rect)
                                              :height content-height})))
                         scroll-window)))

    om/IWillUnmount
    (will-unmount [_]
      (scroll/dispose owner))))

(defn notices [data owner]
  (reify
    om/IRender
    (render [_]
      (html
       (let [build-data (:build-data data)
             project-data (:project-data data)
             plan (:plan project-data)
             project (:project project-data)
             build (:build build-data)]
         [:div.notices
          (common/messages (set (:messages build)))
          [:div.row
           [:div.col-xs-12
            (when (empty? (:messages build))
              [:div (report-error {:build build
                                   :show-premium-content? (project-model/show-premium-content? project plan)}
                                  owner)])

            (when (and plan (project-common/show-trial-notice? project plan))
              (om/build project-common/trial-notice project-data))

            (when (plan-model/suspended? plan)
              (om/build project-common/suspended-notice {:plan plan
                                                         :vcs_type (:vcs_type project)}))

            (when (and project (project-common/show-enable-notice project))
              (om/build project-common/enable-notice project))

            (when (build-model/display-build-invite build)
              (om/build invites/build-invites
                        (:invite-data data)
                        {:opts {:vcs_type (vcs-url/vcs-type (:vcs_url build))
                                :project-name (vcs-url/project-name (:vcs_url build))}}))]]])))))

(defn container-result-icon [{:keys [name]} owner]
  (reify
    om/IRender
    (render [_]
      (om/build svg {:class "container-status-icon"
                              :src (utils/cdn-path (str "/img/inner/icons/" name ".svg"))}))))

(defn last-action-end-time
  [container]
  (-> (filter #(not (:filler-action %)) (:actions container)) last :end_time))

(defn action-duration-ms
  [{:keys [start_time end_time] :as action}]
  (if (and start_time end_time)
    (let [start (.getTime (js/Date. start_time))
          end (if end_time
                (.getTime (js/Date. end_time))
                (datetime/server-now))]
      (- end start))
      0))

;; TODO this seems a little bit slow when calculating durations for really complex builds with
;; lots of containers. Is there a good way to avoid recalculating this when selecting container pills? (Perhaps caching the calculated value using IWillUpdate / IShouldUpdate?)
(defn container-utilization-duration
  [actions]
  (apply + (map action-duration-ms actions)))


(defn container-duration-label [{:keys [actions]}]
  (reify
    om/IRender
    (render [_]
      (html
        [:span.lower-pill-section
         "("
         (datetime/as-duration (container-utilization-duration actions))
         ")"]))))

(defn compute-override-status
  "This is called when properties are updated in will-receive-props.

  This exists because the jump between :running and :waiting status is
  jarring and it can happen very quickly in a sequence of short build
  steps. To mitigate this, we are introducing a two second delay when
  transitioning from the :running state to the :waiting state.

  compute-override-status checks if we are transitioning to
  the :waiting status.  If we are, it generates 'override' data to put
  into component local state.  This data is used during rendering to
  decide which status to display."
  [current-status next-status]
  (if (and (= next-status :waiting)
           (not= current-status next-status))
    {:status current-status
     :until (+ (datetime/now)
               2000)}))

(defn maybe-override-status
  "If there is an override status and its time has not experied, use
  that status.  If the time has expried, use the real status."
  [real-status {:keys [until] :as override-status}]
  (if (and override-status
           (>= until (datetime/now)))
    (:status override-status)
    real-status))

(defn container-pill [{:keys [container status current-container-id build-running? build-finished?]} owner]
  (reify
    om/IDisplayName
    (display-name [_] "Container Pill v2")
    om/IDidMount
    (did-mount [_]
      (timer/set-updating! owner (not (last-action-end-time container))))
    om/IDidUpdate
    (did-update [_ _ _]
      (timer/set-updating! owner (not (last-action-end-time container))))
    om/IWillReceiveProps
    (will-receive-props [this next-props]
      (let [next-status (:status next-props)]
        (om/set-state! owner :override-status (compute-override-status status next-status))))
    om/IRenderState
    (render-state [_ {:keys [override-status]}]
      (html
       (let [container-id (container-model/id container)
             status (maybe-override-status status override-status)
             icon-name (case status
                         :failed "Status-Failed"
                         :success "Status-Passed"
                         :canceled "Status-Canceled"
                         :running "Status-Running"
                         :waiting "Status-Queued"
                         nil)]
         [:a.container-selector
          {:on-click #(raise! owner [:container-selected {:container-id container-id}])
           :class (concat (container-model/status->classes status)
                          (when (= container-id current-container-id) ["active"]))}
          [:span.upper-pill-section
           [:span.container-index (str (:index container))]
           [:span.status-icon
            (om/build container-result-icon {:name icon-name})]]
          (when build-finished?
            (om/build container-duration-label {:actions (:actions container)}))])))))

(def paging-width 10)

(defrender container-controls [{:keys [current-filter containers categorized-containers]} owner]
  (let [filters [{:filter :all
                  :containers containers
                  :label "All"}
                 {:filter :success
                  :containers (:success categorized-containers)
                  :label "Successful"}
                 {:filter :failed
                  :containers (:failed categorized-containers)
                  :label "Failed"}]]
    (html
     [:.controls
      [:.filtering
       [:span.filter-help "Show containers:"]
       (for [{:keys [filter containers label]} filters
             :let [c-name (name filter)
                   id (gstring/format "containers-filter-%s" c-name)
                   cnt (count containers)]]
         (list
          [:input {:id id
                   :type "radio"
                   :name "container-filter"
                   :checked (= current-filter filter)
                   :disabled (zero? cnt)
                   :on-change #(raise! owner [:container-filter-changed {:new-filter filter :containers containers}])}]
          [:label {:for id}
           (gstring/format "%s (%s)" label cnt)]))]])))

(defn- paging-offset-to-display-container
  "Given the id of a container and a vector of containers which will be
  displayed, calculate the paging offset which will cause that container to be
  part of the displayed page. If the container is not found in containers,
  returns nil."
  [container-id containers]
  ;; NB: :index here gets the container's id, or number, also called its "index"
  ;; in our domain. index-into-containers is the index within the given
  ;; containers vector of the container with the given id. Not the same
  ;; meaning of "index".
  (let [index-into-containers (find-index #(= container-id (:index %)) containers)]
    (when index-into-containers
      (* paging-width (js/Math.floor (/ index-into-containers paging-width))))))

(defn container-pills [data owner]
  (reify
    om/IDisplayName
    (display-name [_]
      "Container Pills")
    om/IRender
    (render [_]
      (let [container-data (:container-data data)
            build-running? (:build-running? data)
            build (:build data)
            {:keys [containers current-container-id current-filter paging-offset]} container-data
            categorized-containers (group-by #(container-model/status % build-running?) containers)

            filtered-containers (condp some [current-filter]
                                  #{:all} containers
                                  #{:success :failed} :>> categorized-containers)

            paging-offset (or paging-offset
                              ;; A nil paging-offset means "display whatever page the selected container is on".
                              (paging-offset-to-display-container current-container-id filtered-containers)
                              ;; If the selected container isn't in filtered-containers, show the first page.
                              0)

            container-count (count filtered-containers)
            previous-container-count (max 0 (- paging-offset 1))
            subsequent-container-count (min paging-width (- container-count (+ paging-offset paging-width)))
            project (get-in data [:project-data :project])
            {{plan-org-name :name, plan-vcs-type :vcs_type} :org, :as plan} (get-in data [:project-data :plan])
            show-upsell? (project-model/show-upsell? project plan)
            div (html
                 [:div.container-list {:class (when (and (> previous-container-count 0)
                                                         (> subsequent-container-count 0))
                                                "prev-and-next")}
                  (if (pos? previous-container-count)
                    [:a.container-selector.page-container-pills
                     {:on-click #(raise! owner [:container-paging-offset-changed {:paging-offset (- paging-offset paging-width)}])}
                     [:div.nav-caret
                      [:i.fa.fa-2x.fa-angle-left]]
                     [:div.pill-details ;; just for flexbox container
                      [:div "Previous " paging-width]
                      [:div container-count " total"]]])
                  (for [container (subvec filtered-containers
                                          paging-offset
                                          (min container-count (+ paging-offset paging-width)))]
                    (om/build container-pill
                              {:container container
                               :build-running? build-running?
                               :build-finished? (build-model/finished? build)
                               :current-container-id current-container-id
                               :status (container-model/status container build-running?)}
                              {:react-key (:index container)}))
                  (cond

                    (pos? subsequent-container-count)
                    [:a.container-selector.page-container-pills
                     {:on-click #(raise! owner [:container-paging-offset-changed {:paging-offset (+ paging-offset paging-width )}])}
                     [:div.pill-details ;; just for flexbox container
                      [:div "Next " subsequent-container-count]
                      [:div container-count " total"]]
                     [:div.nav-caret
                      [:i.fa.fa-2x.fa-angle-right]]]

                    (project-model/parallel-available? project)
                    [:a.container-selector.add-containers
                     {:href (routes/v1-org-settings-path {:org plan-org-name
                                                          :vcs_type plan-vcs-type
                                                          :_fragment "linux-pricing"})
                      :title "Adjust containers"
                      :class (when show-upsell? "upsell")}
                     (if show-upsell?
                       [:span "Add Containers +"]
                       [:span "+"])])])]
        (html
         [:div.container-pills-container
          (when (and (project-model/parallel-available? project)
                     (build-model/finished? build))
            (om/build container-controls {:current-filter current-filter
                                          :containers containers
                                          :categorized-containers categorized-containers
                                          :build-running? build-running?}))
          (om/build sticky {:content div :content-class "containers"})])))))

(def css-trans-group (-> js/React
                         (aget "addons")
                         (aget "CSSTransitionGroup")
                         js/React.createFactory))

(defn transition-group
  [opts & components]
  (let [[group-name enter? leave? appear? class-name]
        (if (map? opts)
          [(:name opts)
           (:enter opts)
           (:leave opts)
           (:appear opts)
           (:class opts)]
          [opts true true false nil])]
    (apply
      css-trans-group
      #js {:transitionName group-name
           :transitionEnter enter?
           :transitionLeave leave?
           :transitionAppear appear?
           :component "div"
           :className class-name}
      components)))

(defn selected-container-index [data]
  (get-in data [:current-build-data :container-data :current-container-id]))

(defn build [{:keys [app ssh-available?]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:action-transition-direction "steps-ltr"})
    om/IWillReceiveProps
    (will-receive-props [_ next-props]
      (let [old-ix (selected-container-index (om/get-props owner))
            new-ix (selected-container-index next-props)]
        (om/set-state! owner
                       :action-transition-direction
                       (if (> old-ix new-ix)
                         "steps-ltr"
                         "steps-rtl"))))
    om/IRender
    (render [_]
      (let [build (get-in app state/build-path)
            build-data (get-in app state/build-data-path)
            container-data (get-in app state/container-data-path)
            invite-data (get-in app state/build-invite-data-path)
            project-data (get-in app state/project-data-path)
            user (get-in app state/user-path)]
        (html
         [:div.build-info-v2
          (if-not build
           [:div
             (om/build common/flashes (get-in app state/error-message-path))
             [:div.loading-spinner-big common/spinner]]

            [:div
             (om/build build-head/build-head {:build-data (dissoc build-data :container-data)
                                              :current-tab (get-in app state/navigation-tab-path)
                                              :project-data project-data
                                              :user user
                                              :scopes (get-in app state/project-scopes-path)
                                              :ssh-available? ssh-available?})
             [:div.card.col-sm-12

              (om/build common/flashes (get-in app state/error-message-path))

              (om/build notices {:build-data (dissoc build-data :container-data)
                                 :project-data project-data
                                 :invite-data invite-data})

              (om/build container-pills {:container-data container-data
                                         :build-running? (build-model/running? build)
                                         :build build
                                         :project-data project-data})

              (transition-group {:name (om/get-state owner :action-transition-direction)
                                 :enter true
                                 :leave true
                                 :class "build-steps-animator"}
                                (om/build build-steps/container-build-steps
                                           container-data
                                           {:key :current-container-id}))]])])))))
