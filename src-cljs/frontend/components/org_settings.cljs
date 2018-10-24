(ns frontend.components.org-settings
  (:require [cljs.core.async :as async :refer [<! chan close!]]
            clojure.set
            [clojure.string :as string]
            [frontend.analytics.track :as analytics-track]
            [frontend.async :refer [navigate! raise!]]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.components.inputs :as inputs]
            [frontend.components.org-settings.contexts :as contexts]
            [frontend.components.pieces.button :as button]
            [frontend.components.pieces.card :as card]
            [frontend.components.pieces.form :as form]
            [frontend.components.pieces.modal :as modal]
            [frontend.components.pieces.spinner :refer [spinner]]
            [frontend.components.pieces.table :as table]
            [frontend.components.pieces.tabs :as tabs]
            [frontend.components.pieces.top-banner :refer [banner]]
            [frontend.components.project.common :as project-common]
            [frontend.config :as config]
            [frontend.datetime :as datetime]
            [frontend.models.feature :as feature]
            [frontend.models.organization :as org-model]
            [frontend.models.user :as user]
            [frontend.models.plan :as pm]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.stripe :as stripe]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.string :as gstring]
            [inflections.core :as infl :refer [pluralize]]
            [om.core :as om :include-macros true])
  (:require-macros [cljs.core.async.macros :as am :refer [go]]
                   [frontend.utils :refer [html]]))

(declare osx-overview)
(declare osx-plan-overview)
(declare linux-plan-overview)

(def stripe-email-key :name)

(defn non-admin-plan [{:keys [org-name login vcs_type]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         (card/titled {:title "Organization Admin Permission Needed"}
           (html
             [:div.body
               [:p "To view/edit this page, you must be an organization admin on your VCS-provider (e.g., Github)."]
               [:p "As a non-admin, you may still create a plan for this organization by signing up for a plan under your "
                [:a {:href (routes/v1-org-settings-path {:org login
                                                         :vcs_type vcs_type
                                                         :_fragment "containers"})}
                 "personal organization"]
                " and then "
                [:a {:href (routes/v1-org-settings-path {:org login
                                                         :vcs_type vcs_type
                                                         :_fragment "organizations"})}
                 "paying for/transfering"]
                " your personal plan to " org-name "."]]))]))))

(defn users [app owner]
  (reify
    om/IRender
    (render [_]
      (let [users (get-in app state/org-users-path)
            projects (get-in app state/org-projects-path)
            org-name (get-in app state/org-name-path)
            projects-by-follower (org-model/projects-by-follower projects)
            sorted-users (sort-by (fn [u]
                                    (- (count (get projects-by-follower (:login u)))))
                                  users)]
        (html
         [:div.users
          [:article
           [:legend
            "CircleCI users in the " org-name " organization"]
           [:div
            (if-not (seq users)
              [:h4 "No users found."])
            [:div
             (for [user sorted-users
                   :let [login (:login user)
                         followed-projects (get projects-by-follower login)]]
               [:div.well.om-org-user
                {:class (if (zero? (count followed-projects))
                          "fail"
                          "success")}

                [:div.om-org-user-projects-container
                 [:div.om-org-user-projects
                  [:h3.heading
                   [:img.gravatar {:src (gh-utils/make-avatar-url user :size 60)}]
                   (if (seq followed-projects)
                     (str login " is following:")
                     (str login " is not following any " org-name  " projects"))]
                  (for [project (sort-by (fn [p] (- (count (:followers p)))) followed-projects)
                        :let [vcs-url (:vcs_url project)]]
                    [:div.om-org-user-project
                     [:a {:href (routes/v1-project-dashboard-path {:org (vcs-url/org-name vcs-url)
                                                                   :repo (vcs-url/repo-name vcs-url)
                                                                   :vcs_type (vcs-url/vcs-type vcs-url)})}
                      (vcs-url/project-name vcs-url)]])]]])]]]])))))

(defn followers-container [followers owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.followers-container
        [:div.row-fluid
         (for [follower followers]
           [:span.follower-container
            {:style {:display "inline-block"}
             :title (:login follower)
             :data-toggle "tooltip"
             :data-placement "right"}
            [:img.gravatar
             {:src (gh-utils/make-avatar-url follower :size 60)}]
            " "
            [:span (:login follower)]])]]))))

(defn projects [app owner]
  (reify
    om/IRender
    (render [_]
      (let [users (get-in app state/org-users-path)
            projects (get-in app state/org-projects-path)
            {followed-projects true unfollowed-projects false} (group-by #(pos? (count (:followers %)))
                                                                         projects)
            org-name (get-in app state/org-name-path)]
        (html
         [:div
          [:div.followed-projects.row-fluid
           [:article
            [:legend "Followed projects"]
            (if-not (seq followed-projects)
              [:h3 "No followed projects found."]

              [:div.span8
               (for [project followed-projects
                     :let [vcs-url (:vcs_url project)]]
                 [:div.row-fluid
                  [:div.span12.well

                    [:div.project-header
                     [:span.project-name
                      [:a {:href (routes/v1-project-dashboard-path {:org (vcs-url/org-name vcs-url)
                                                                    :repo (vcs-url/repo-name vcs-url)
                                                                    :vcs_type (vcs-url/vcs-type vcs-url)})}
                       (vcs-url/project-name vcs-url)]
                      " "]
                     [:div.github-icon
                      [:a {:href vcs-url}
                       [:i.octicon.octicon-mark-github]]]
                     [:div.settings-icon
                      [:a.edit-icon {:href (routes/v1-project-settings-path {:org (vcs-url/org-name vcs-url)
                                                                             :repo (vcs-url/repo-name vcs-url)
                                                                             :vcs_type (vcs-url/vcs-type vcs-url)})}
                       [:i.material-icons "settings"]]]]
                   (om/build followers-container (:followers project))]])])]
           [:div.row-fluid
            [:h1 "Untested projects"]
            (if-not (seq unfollowed-projects)
              [:h3 "No untested projects found."]

              [:div.span8
               (for [project unfollowed-projects
                     :let [vcs-url (:vcs_url project)]]
                 [:div.row-fluid
                  [:div.span12.well

                    [:div.project-header
                     [:span.project-name
                      [:a {:href (routes/v1-project-dashboard-path {:org (vcs-url/org-name vcs-url)
                                                                    :repo (vcs-url/repo-name vcs-url)
                                                                    :vcs_type (vcs-url/vcs-type vcs-url)})}
                       (vcs-url/project-name vcs-url)]
                      " "]
                     [:div.github-icon
                      [:a {:href vcs-url}
                       [:i.octicon.octicon-mark-github]]]
                     [:div.settings-icon
                      [:a.edit-icon {:href (routes/v1-project-settings-path {:org (vcs-url/org-name vcs-url)
                                                                             :repo (vcs-url/repo-name vcs-url)
                                                                             :vcs_type (vcs-url/vcs-type vcs-url)})}
                       [:i.material-icons "settings"]]]]
                   (om/build followers-container (:followers project))]])])]]])))))

(defn plans-trial-notification [plan org-name owner]
  [:div.row-fluid
   [:div.alert.alert-success {:class (when (pm/trial-over? plan) "alert-error")}
    [:p
     (if (pm/trial-over? plan)
       "Your 2-week trial is over!"

       [:span "The " [:strong org-name] " organization has "
        (pm/pretty-trial-time plan) " left in its trial."])]
    [:p "Your trial is equivalent to a plan with" (pluralize (pm/trial-containers plan) "containers") "."]
    (when (and (not (:too_many_extensions plan))
               (> 3 (pm/days-left-in-trial plan)))
      [:p
       "Need more time to decide? "
       [:a {:href "mailto:sayhi@circleci.com"} "Get in touch."]])]])

(defn piggieback-plan-wording [plan]
  (let [containers (pm/paid-linux-containers plan)]
    (str
      (when (pos? containers)
        (str containers " paid Linux containers"))
      (when (and (pos? containers) (pm/osx? plan))
        " and the OS X ")
      (when (pm/osx? plan)
        (-> plan :osx :template :name)) " plan.")))

(defn plans-piggieback-plan-notification [{{parent-name :name
                                            parent-vcs-type :vcs_type} :org
                                           :as plan}
                                          current-org-name]


  [:div
   (card/titled {:title "This organization's plan is covered under another organization's plan"}
              (html
                [:div
                 [:p
                  "This organization is covered under the " [:strong [:em parent-name]] " organization's plan which has " [:strong (piggieback-plan-wording plan)]]
                 [:p
                  "If you're an admin in the " [:strong [:em parent-name]]
                  " organization, then you can change plan settings from the "
                  [:strong [:a {:href (routes/v1-org-settings-path {:org parent-name
                                                                    :vcs_type parent-vcs-type})}]
                   parent-name " plan page"]] "."
                 [:p.mb-0
                  "You can create a separate plan for " [:strong [:em current-org-name]] " when you're no longer covered by " [:strong [:em parent-name]] "."]]))])

(defn plural-multiples [num word]
  (if (> num 1)
    (pluralize num word)
    (gstring/format "%s %s" num word)))

(defn pluralize-no-val [num word]
  (if (= num 1) (infl/singular word) (infl/plural word)))

(def osx-faq-items
  [{:question "Why would I choose an OS X plan (as opposed to a Linux Plan)?"
    :answer [[:div "If you develop for Apple-related software (e.g., you are an iOS developer), you will likely need an OS X plan to ensure your tests run on our secure, private cloud of OS X machines."]
             [:div "OS X plans start with a two-week trial with access to our Growth Plan (7x concurrent builds). At the end of your two-week trial, you may choose the plan that fits your needs best."]
             [:div "Linux plans allow customers to build on multiple isolated Linux systems. All customers get 1 free linux container and then Linux plans offer access to additional containers available at $50/container."]]}

   {:question "What plan do I need?"
    :answer [[:div "We’ve provided recommendations based on the ranges of builds but every team is different regarding their frequency and length of builds - as well as their need for engineer support."]
             [:div "To solve for this, everyone starts with a free 2-week trial at the Growth Plan which will help you determine all of those factors. We are happy to help you work through which plan is right for your team."]]}

   {:question "What is concurrency?"
    :answer [[:div "Concurrency refers to running multiple jobs at the same time (e.g., with 2x concurrency, two builds triggered at the same time will both kick off, but on 1x concurrency one build will queue, waiting for resources)."]
             [:div "Concurrency avoids slow-downs in work as your builds may otherwise queue behind the builds of someone else on your team."]]}


   {:question "*What if I go over the minutes allotted for a given plan?"
    :answer [[:div  "Minutes and overages ensure we can stabilize capacity while offering as much power as possible which should hopefully lead to the greatest possible utility all around."]
             [:p "Overages are as follows:"]
             [:ul.overage-list
              [:li "Seed & Startup: .08/minute"]
              [:li "Growth: .05/minute"]
              [:li "Mobile Focused: .035/minute"]]
             [:div "Users will be alerted in-app as they approach the limit and upon passing their respective limit."]
             [:div
              "Feel free to reach out to "
              [:a {:href "mailto:billing@circleci.com"} "billing@circleci.com"]
              " with any additional questions"]]}

   {:question "Can I change my plan at a later time? Can I cancel anytime?"
    :answer [[:div "Yes and yes!"]
             [:div "You can visit this page to upgrade, downgrade, or cancel your plan at any time."]]}

   {:question "What if I want something custom?"
    :answer [[:div "Feel free to contact us "
              [:a {:href "mailto:billing@circleci.com"} "billing@circleci.com"]]]}

   {:question "What if I am building open-source?"
    :answer [[:div "We also offer the Seed plan (at 1x concurrency) for OS X open-source projects. Contact us at "
              [:a {:href "mailto:billing@circleci.com"} "billing@circleci.com"]
              " for access. If you are building a bigger open-source project and need more resources, let us know how we can help you!"]]}])

(def linux-faq-items
  [{:question "How do I get started?"
    :answer [[:div "Linux plans start with the ability to run one build simultaneously without parallelism at no charge. Open source projects get 3 additional free containers, so you can use the power of parallelism and/or run more than one build concurrently. Purchasing a Linux plan enables access to additional containers at $50/container/month."]
             [:div "During the signup process, you can decide which plan(s) you need - you can build for free regardless!"]]}

   {:question "How do containers work?"
    :answer [[:div "Every time you push to your VCS system, we checkout your code and run your build inside of a fresh, on-demand, and isolated Linux container pre-loaded with most popular languages, tools, and framework. CircleCI, in many cases, will detect and automatically download and cache your dependencies, and you can fully script any steps or integrations."]]}

   {:question "What is concurrency? What is parallelism?"
    :answer [[:div "Concurrency refers to utilizing multiple containers to run multiple builds at the same time. Otherwise, if you don't have enough free containers available, your builds queue up until other builds finish."]
             [:div "Parallelism splits a given build’s tests across multiple containers, allowing you to dramatically speed up your test suite. This enables your developers to finish even the most test-intensive builds in a fraction of the time."]]}

   {:question "How many containers do I need?"
    :answer [[:div "Most of our customers tend to use about 2-3 containers per full-time developer. Every team is different, however, and we're happy to set you up with a trial to help you figure out how many works best for you. As your team grows and/or as the speed of your build grows you can scale to any number of containers at any level of parallelism and concurrency is right for your team."]]}

   {:question "Why should I trust CircleCI with my code?"
    :answer [[:div "Security is one of our top priorities. Read our security policy to learn more about why many other customers rely on CircleCI to keep their code safe."]]}

   {:question "Can I change my plan at a later time? Can I cancel anytime?"
    :answer [[:div "Yes and yes! We offer in-app tools to fully control your plan and have account managers and an international support team standing by when you need help."]]}

   {:question "What if I want something custom?"
    :answer [[:div "Feel free to contact us "
              [:a {:href "mailto:billing@circleci.com"} "billing@circleci.com"]]]}

   {:question "What if I am building open-source?"
    :answer [[:div "We offer a total of four free linux containers ($2400 annual value) for open-source projects. Simply keeping your project public will enable this for you!"]]}])

(defn student-pack-faq-answer [{:keys [org-name vcs-type personal-org?]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         [:p
          "If you are a verified student on Github, you can "
          (if personal-org?
            (forms/managed-button
              [:a
               {:data-success-text "Success!"
                :data-loading-text "Activating..."
                :data-failed-text "Failed"
                :on-click #(raise! owner [:activate-plan-trial {:plan-type pm/linux-key
                                                                :template pm/student-trial-template
                                                                :org {:name org-name
                                                                      :vcs_type vcs-type}}])}
               "activate"])
            "activate")
          " our student plan to get two additional free linux containers ($1200 annual value) for only your own personal organization (the organization that has your username)."]
         [:p
          "This plan lasts as long as Github recognizes your account as a student account, or until you decide to upgrade to a paid plan. Learn more on Github's "
          [:a {:href "https://education.github.com/pack" :target "_blank"}
           "Student Pack Page"]
          "."]]))))

(defn student-pack-faq-item
  [data]
  {:question "What if I am a Student?"
   :answer [(om/build student-pack-faq-answer data)]})

(defn faq-answer-line [answer-line owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.answer-line answer-line]))))

(defn faq-item [{:keys [question answer]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div.faq-item
         [:h1.question question]
         [:div.answer
          (om/build-all faq-answer-line answer)]]))))

(defn faq [items owner]
  (reify
    om/IRender
    (render [_]
      (let [[first-half second-half] (split-at (quot (count items) 2) items)]
       (html
         [:fieldset.faq {:data-component `faq}
          [:legend "FAQs"]
          [:div.columns
           [:div.column
            (om/build-all faq-item first-half)]
           [:div.column
            (om/build-all faq-item second-half)]]])))))

(defn plan-payment-button [{:keys [text loading-text disabled? on-click-fn]} owner]
  (reify
    om/IRender
    (render [_]
      (button/managed-button
       {:success-text "Success!"
        :loading-text loading-text
        :failed-text "Failed"
        :on-click on-click-fn
        :kind :primary
        :fixed? true
        :disabled? disabled?}
       text))))

(defn cancel-plan-modal
  [{:keys [app close-fn plan-type-key]} owner]
  (let [org-name (get-in app state/org-name-path)
        vcs_type (get-in app state/org-vcs_type-path)
        plan (get-in app state/org-plan-path)
        plan-template (if (= plan-type-key pm/linux-key)
                        (pm/linux-template plan)
                        (pm/osx-template plan))
        plan-type (if (= plan-type-key pm/linux-key)
                    pm/linux-plan-type
                    pm/osx-plan-type)
        plan-id (:id plan-template)
        track-properties {:plan-template plan-template
                          :plan-id plan-id
                          :plan-type plan-type
                          :vcs_type vcs_type}]
    (reify
      om/IDidMount
      (did-mount [_]
        ((om/get-shared owner :track-event) {:event-type :cancel-plan-modal-impression
                                                         :properties {:plan-id plan-id
                                                                      :plan-template plan-template
                                                                      :plan-type plan-type
                                                                      :vcs_type vcs_type}}))
      om/IRender
      (render [_]
        (modal/modal-dialog
          {:title "Are you sure?"
           :body
           (html
            [:.inner.modal-contents
             [:div.org-cancel
              [:div.top-value
               [:p.value-prop "By switching to our free plan, you will lose access to:"]
               [:ul
                [:li.premium-feature "Build History"]
                [:li.premium-feature "Parallelism"]
                [:li.premium-feature "Concurrency"]
                [:li.premium-feature "Engineer Support"]
                [:li.premium-feature "Insights"]
                [:li.premium-feature "Other Premium Features"]]]
              [:div.bottom-value
               [:p.value-prop "Your cancelation will be effective immediately."]]
              [:div.row-fluid
               [:p
                {:data-bind "attr: {alt: cancelFormErrorText}"}
                "Please tell us why you're canceling. This helps us make CircleCI better!"]
               [:form
                (for [reason [{:value "project-ended", :text "Project Ended"},
                              {:value "slow-performance", :text "Slow Performance"},
                              {:value "unreliable-performance", :text "Unreliable Performance"},
                              {:value "too-expensive", :text "Too Expensive"},
                              {:value "didnt-work", :text "Couldn't Make it Work"},
                              {:value "missing-feature", :text "Missing Feature"},
                              {:value "poor-support", :text "Poor Support"},
                              {:value "other", :text "Other"}]]
                  [:label.cancel-reason
                   [:input
                    {:checked (get-in app (state/selected-cancel-reason-path (:value reason)))
                     :on-change #(utils/toggle-input owner (state/selected-cancel-reason-path (:value reason)) %)
                     :type "checkbox"}]
                   (:text reason)])
                [:textarea
                 {:required true
                  :value (get-in app state/cancel-notes-path)
                  :on-change #(utils/edit-input owner state/cancel-notes-path %)}]
                [:label
                 {:placeholder "Thanks for the feedback!",
                  :alt (if (get app (state/selected-cancel-reason-path "other"))
                         "Would you mind elaborating more?"
                         "Have any other thoughts?")}]]]]])
           :close-fn #(do (analytics-track/cancel-plan-modal-dismissed
                            (merge track-properties {:current-state app :component "close-x"}))
                          (close-fn))
           :actions [(button/button
                       {:on-click #(do (analytics-track/cancel-plan-modal-dismissed
                                         (merge track-properties {:current-state app :component "close-button"}))
                                       (close-fn))}
                       "Close")
                     (let [reasons (->> (get-in app state/selected-cancel-reasons-path)
                                        (filter second)
                                        keys
                                        set)
                           notes (get-in app state/cancel-notes-path)
                           needs-other-notes? (and (contains? reasons "other") (string/blank? notes))
                           errors (cond (empty? reasons) "Please select at least one reason."
                                        needs-other-notes? "Please specify above."
                                        :else nil)
                           enable-button? (or (empty? reasons) needs-other-notes?)]
                       (button/managed-button
                         {:kind :danger
                          :disabled? enable-button?
                          :success-text "Canceled"
                          :loading-text "Canceling..."
                          :on-click #(raise! owner [:cancel-plan-clicked
                                                     (merge track-properties {:org-name org-name
                                                                              :cancel-reasons reasons
                                                                              :cancel-notes notes
                                                                              :on-success close-fn
                                                                              :plan-type-key plan-type-key})])}
                         "Cancel Plan"))]})))))

(defn osx-plan [{:keys [app title price container-count daily-build-count max-minutes support-level team-size
                        plan-id plan trial-starts-here? org-name vcs-type]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})
    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [plan-data (get-in pm/osx-plans [plan-id])
            currently-selected? (= (name plan-id) (pm/osx-plan-id plan))
            on-trial? (and trial-starts-here? (pm/osx-trial-plan? plan))
            trial-expired? (and on-trial? (not (pm/osx-trial-active? plan)))
            trial-starts-here? (and trial-starts-here?
                                    (pm/trial-eligible? plan :osx)
                                    (not (pm/osx? plan)))
            plan-type pm/osx-plan-type
            osx-key pm/osx-key
            close-fn #(om/set-state! owner :show-modal? false)
            existing-plan-id (pm/osx-plan-id plan)]
        (html
          [:div {:data-component `osx-plan}
           [:div {:class (cond currently-selected? "plan-notice selected-notice"
                               trial-expired? "plan-notice trial-expired-notice"
                               on-trial? "plan-notice selected-notice"
                               trial-starts-here?  "plan-notice trial-notice"
                               :else "no-notice")}
            [:div.plan
             [:div.header
              [:div.title title]
              [:div.price "$" [:span.bold price] "/mo"]]
             (when show-modal?
               (om/build cancel-plan-modal {:app app
                                            :close-fn close-fn
                                            :plan-type-key osx-key}))
             [:div.content
              [:div.containers [:span.bold container-count] " OS X concurrency"]
              [:div.daily-builds
               [:div "Recommended for teams building "]
               [:div.bold daily-build-count " builds/day"]]
              [:div.max-minutes [:span.bold max-minutes] " max minutes/month" [:sup.bold "*"]]
              [:div.support support-level]
              [:div.team-size "Recommended for " [:span.bold team-size]]
              [:div " team members"]]
             [:div.action
              (if (pm/stripe-customer? plan)
                (om/build plan-payment-button {:text "Update"
                                               :loading-text "Updating..."
                                               :disabled? (= (name plan-id) (pm/osx-plan-id plan))
                                               :on-click-fn #(do
                                                               (raise! owner [:update-osx-plan-clicked {:plan-type {:template (name plan-id)}}])
                                                               (analytics-track/update-plan-clicked {:owner owner
                                                                                                     :new-plan plan-id
                                                                                                     :previous-plan existing-plan-id
                                                                                                     :plan-type plan-type
                                                                                                     :upgrade? (> (:price plan-data) (pm/osx-cost plan))}))})
                (om/build plan-payment-button {:text "Pay Now"
                                               :loading-text "Paying..."
                                               :on-click-fn #(do
                                                               (raise! owner [:new-osx-plan-clicked {:plan-type {:template (name plan-id)}
                                                                                                     :price (:price plan-data)
                                                                                                     :description (gstring/format "OS X %s - $%d/month "
                                                                                                                                  (clojure.string/capitalize (name plan-id))
                                                                                                                                  (:price plan-data))}])
                                                               ((om/get-shared owner :track-event) {:event-type :new-plan-clicked
                                                                                                    :properties {:plan-type plan-type
                                                                                                                 :plan plan-id}}))}))

              (if (not (pm/osx? plan))
                (when trial-starts-here?
                  [:div.start-trial "OR" [:br]
                   (forms/managed-button
                     [:a
                      {:data-success-text "Success!"
                       :data-loading-text "Starting..."
                       :data-failed-text "Failed"
                       :on-click #(raise! owner [:activate-plan-trial {:plan-type osx-key
                                                                       :template pm/osx-trial-template
                                                                       :org (:org plan)}])}
                      "start a 2-week free trial"])])
                (when currently-selected?
                  [:div.cancel-plan "OR" [:br]
                   [:a {:on-click #(om/set-state! owner :show-modal? true)}
                      "cancel your current OSX plan"]]))]]

            (cond
              trial-starts-here?
              [:div.bottom "FREE TRIAL STARTS HERE"]

              trial-expired?
              [:div.bottom "Trial has Ended - Choose a Plan"]

              on-trial?
              [:div.bottom
               (str "Trial plan ("(pm/osx-trial-days-left plan)" left)")]

              currently-selected?
              [:div.bottom "Your Current Plan"])]])))))

(defn show-org-settings-error-banner
  [owner message banner-key]
  (om/build banner {:banner-type "danger"
                    :content [:span message]
                    :dismiss-fn #(raise! owner [banner-key false])
                    :owner owner}))

(defn maybe-show-error-banner!
  [app owner]
  (when (get-in app state/show-stripe-error-banner-path)
    (show-org-settings-error-banner owner "Unable to process payment." :toggle-stripe-error-banner))
  (when (get-in app state/show-admin-error-banner-path)
    (show-org-settings-error-banner owner "We're sorry, but there appears to be an error! This usually happens when a non-admin attempts to access or update this page." :toggle-admin-error-banner)))

(defn osx-plans-list [{:keys [plan org-name vcs-type app]} owner]
  (reify
    om/IRender
    (render [_]
      (let [osx-plans (->> pm/osx-plans
                           (vals)
                           (map (partial merge {:plan plan})))]
        (html
          [:div.osx-plans {:data-component `osx-plans-list}
           (if (pm/osx? plan)
             [:legend.update-plan "Update OS X Plan"]
             [:legend.update-plan "Choose OS X Plan"])
           [:p [:em "Your selection below only applies to OS X service and will not affect Linux containers."]]
           (when (and (pm/osx-trial-plan? plan) (not (pm/osx-trial-active? plan)))
             [:p "The OS X trial you've selected has expired, please choose a plan below."])
           (when (and (pm/osx-trial-plan? plan) (pm/osx-trial-active? plan))
             [:p (gstring/format "You have %s left on the OS X trial." (pm/osx-trial-days-left plan))])
           (maybe-show-error-banner! app owner)
           [:div.plan-selection
            (om/build-all osx-plan (->> osx-plans
                                        (map #(assoc % :org-name org-name :vcs-type vcs-type :app app))))]])))))
(defn linux-oss-alert [app owner]
  (om/component
    (html
      [:div.usage-message
       [:div.text
        [:span "We "[:span.heart-emoji "❤"]" OSS. Projects that are public will build with " pm/oss-containers " extra containers - our gift to free and open source software."]]])))

(defn linux-plan [{:keys [app checkout-loaded?]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:show-modal? false})
    om/IRenderState
    (render-state [_ {:keys [show-modal?]}]
      (let [org-name (get-in app state/org-name-path)
            vcs-type (get-in app state/org-vcs_type-path)
            plan (get-in app state/org-plan-path)
            selected-containers (or (get-in app state/selected-containers-path)
                                    (if (config/enterprise?)
                                      (pm/enterprise-containers plan)
                                      (pm/paid-linux-containers plan)))
            selected-paid-containers (max 0 selected-containers)
            osx-total (or (some-> plan :osx :template :price) 0)
            old-total (- (pm/stripe-cost plan) osx-total)
            new-total (pm/linux-cost plan (+ selected-containers (pm/freemium-containers plan)))
            linux-container-cost (pm/linux-per-container-cost plan)
            piggiebacked? (pm/piggieback? plan org-name vcs-type)
            button-clickable? (not= (if piggiebacked? 0 (pm/paid-linux-containers plan))
                                    selected-paid-containers)
            containers-str (pluralize-no-val selected-containers "container")
            close-fn #(om/set-state! owner :show-modal? false)]
        (html
         [:div.edit-plan {:class "pricing.page" :data-component `linux-plan}
          [:div.main-content
           [:div.plan-header
            (om/build linux-plan-overview app)

            [:div.split-plan-block
             [:h1 "More containers = faster builds & lower queue times."]
             (when-not (config/enterprise?)
               [:div
                (om/build linux-oss-alert app)])]]
           (when show-modal?
            (om/build cancel-plan-modal {:app app
                                         :close-fn close-fn
                                         :plan-type-key pm/linux-key}))
           [:div
            (when-not (pm/github-marketplace-plan? plan)
              [:form
               (when-not (config/enterprise?)
                 [:div.container-picker
                  (if (pm/linux? plan)
                    [:legend.update-plan "Update Linux Plan"]
                    [:legend.update-plan "Choose Linux Plan"])
                  (maybe-show-error-banner! app owner)
                  [:h1.container-input
                   [:span "Use "]
                   [:input.form-control
                    {:type "text" :value selected-containers
                     :on-change #(utils/edit-input owner state/selected-containers-path %
                                                   :value (int (.. % -target -value)))}]
                   [:span.new-plan-total (if (config/enterprise?)
                                           containers-str
                                           (str "paid " containers-str
                                                (str (when-not (zero? new-total) (str " at $" new-total "/month")))
                                                " + " (str (if (pm/in-student-trial? plan)
                                                             "3 free containers"
                                                             "1 free container"))))]]
                  [:p
                   (str "Our pricing is flexible and scales with you. Add as many containers as you want for $" linux-container-cost "/month each.")
                   [:br]
                   [:em "Changes to your Linux plan will not affect your OS X plan."]]])
               [:fieldset
                (if (and (not piggiebacked?)
                         (or (config/enterprise?)
                             (pm/stripe-customer? plan)))
                  (let [enterprise-text "Save changes"]
                    (if (and (zero? new-total)
                             (not (config/enterprise?))
                             (not (zero? (pm/paid-linux-containers plan))))
                      (button/button
                        {:disabled? (not button-clickable?)
                         :kind :danger
                         :on-click #(om/set-state! owner :show-modal? true)}
                        "Cancel Plan")
                      (button/managed-button
                        {:success-text "Saved"
                         :loading-text "Saving..."
                         :disabled? (not button-clickable?)
                         :on-click (when button-clickable?
                                     #(do
                                        (raise! owner [:update-containers-clicked
                                                       {:containers selected-paid-containers}])
                                        (analytics-track/update-plan-clicked {:owner owner
                                                                              :new-plan selected-paid-containers
                                                                              :previous-plan (pm/paid-linux-containers plan)
                                                                              :plan-type pm/linux-plan-type
                                                                              :upgrade? (> selected-paid-containers (pm/paid-linux-containers plan))})))
                         :kind :primary}
                        (if (config/enterprise?)
                          enterprise-text
                          "Update Plan"))))
                  (if-not checkout-loaded?
                    (spinner)
                    (button/managed-button
                      {:success-text "Paid!"
                       :loading-text "Paying..."
                       :failed-text "Failed!"
                       :disabled? (not button-clickable?)
                       :on-click (when button-clickable?
                                   #(raise! owner [:new-plan-clicked
                                                   {:containers selected-paid-containers
                                                    :linux {:template (:id pm/default-template-properties)}
                                                    :price new-total
                                                    :description (str "$" new-total "/month, includes "
                                                                      (pluralize selected-containers "container"))}]))
                       :kind :primary}
                      "Pay Now")))
                (when-not (config/enterprise?)
                  ;; TODO: Clean up conditional here - super nested and many interactions
                  ;; FIXME CIRCLE-1833 ON CURRENT PLAN, ADD MESSAGE 'CHANGE CONTAINERS TO UPDATE PLAN'
                  (if (or (pm/linux? plan) (and (pm/freemium? plan) (not (pm/in-trial? plan))))
                    [:div
                     [:p.hint
                      [:em (cond
                             (< old-total new-total) "We'll charge your card today, for the prorated difference between your new and old plans."
                             (> old-total new-total) "We'll credit your account, for the prorated difference between your new and old plans.")]]]
                    (when-not (pm/in-student-trial? plan)
                      (if (pm/in-trial? plan)
                        [:span "Your trial will end in " (pluralize (Math/abs (pm/days-left-in-trial plan)) "day")
                         "."]
                        [:span "Your trial of " (pluralize (pm/trial-containers plan) "container")
                         " ended " (pluralize (Math/abs (pm/days-left-in-trial plan)) "day")
                         " ago. Pay now to enable builds of private projects."]))))]])]]])))))

(defn pricing-tabs [{:keys [app plan checkout-loaded? selected-tab-name]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{{org-name :name
              vcs-type :vcs_type} :org} plan]
        (card/tabbed
         {:tab-row
          (om/build tabs/tab-row {:tabs [{:name :linux
                                          :icon (html [:i.fa.fa-linux.fa-lg])
                                          :label "Linux Plan"}
                                         {:name :osx
                                          :icon (html [:i.fa.fa-apple.fa-lg])
                                          :label "OS X Plan"}]
                                  :selected-tab-name selected-tab-name
                                  :on-tab-click #(navigate! owner (routes/v1-org-settings-path {:org org-name
                                                                                                :vcs_type vcs-type
                                                                                                :_fragment (str (name %) "-pricing")}))})}
         (case selected-tab-name
           :linux (list
                   (om/build linux-plan {:app app :checkout-loaded? checkout-loaded?})
                   (om/build faq (cond-> linux-faq-items
                                         (= (feature/ab-test-treatment :github-student-pack) :in-student-pack)
                                         (conj (student-pack-faq-item {:org-name org-name
                                                                       :vcs-type vcs-type
                                                                       :personal-org? (pm/github-personal-org-plan? (get-in app state/user-path) plan)})))))

           :osx (list
                 (om/build osx-plan-overview {:plan plan})
                 (om/build osx-plans-list {:plan plan
                                           :org-name (get-in app state/org-name-path)
                                           :vcs-type (get-in app state/org-vcs_type-path)
                                           :app app})
                 (om/build faq osx-faq-items))))))))

(defn pricing-starting-tab [subpage]
  (get {:osx-pricing :osx
        :linux-pricing :linux} subpage :linux))

(defn cloud-pricing [app owner]
  (reify
    ;; I stole the stateful "did we load stripe checkout code" stuff
    ;; from the plan component above, but the billing-card component
    ;; also has it. What's the nice way to
    ;; abstract it out?
    om/IInitState
    (init-state [_]
      {:checkout-loaded? (stripe/checkout-loaded?)
       :checkout-loaded-chan (chan)})
    om/IWillMount
    (will-mount [_]
      (let [ch (om/get-state owner [:checkout-loaded-chan])
            checkout-loaded? (om/get-state owner [:checkout-loaded?])]
        (when-not checkout-loaded?
          (go (<! ch)
              (utils/mlog "Stripe checkout loaded")
              (om/set-state! owner [:checkout-loaded?] true))
          (utils/mlog "Loading Stripe checkout")
          (stripe/load-checkout ch))))
    om/IDidMount
    (did-mount [_]
      (utils/tooltip "#grandfathered-tooltip-hack" {:animation false}))
    om/IWillUnmount
    (will-unmount [_]
      (close! (om/get-state owner [:checkout-loaded-chan])))
    om/IRenderState
    (render-state [_ {:keys [checkout-loaded?]}]
      (let [plan (get-in app state/org-plan-path)
            org-name (get-in app state/org-name-path)
            org-vcs-type (get-in app state/org-vcs_type-path)]
        (html
          (if-not plan
            (cond ;; TODO: fix; add plan
              (nil? plan)
              (spinner)
              (not (seq plan))
              [:h3 (str "No plan exists for" org-name "yet. Follow a project to trigger plan creation.")]
              :else
                [:h3 "Something is wrong! Please submit a bug report."])

            (if (pm/piggieback? plan org-name org-vcs-type)
              (plans-piggieback-plan-notification plan org-name)
              [:div
               [:legend "Plan Settings"]
               (om/build pricing-tabs {:app app :plan plan :checkout-loaded? checkout-loaded?
                                       :selected-tab-name (pricing-starting-tab (get-in app state/org-settings-subpage-path))})])))))))

(defn piggieback-org-list [piggieback-orgs selected-orgs [{vcs-type :vcs_type} :as vcs-users-and-orgs] owner]
  (let [;; split user orgs from real ones so we can later cons the
        ;; user org onto the list of orgs
        {[{vcs-user-name :login}] nil
         vcs-orgs true}
        (group-by :org vcs-users-and-orgs)
        vcs-org-names (->> vcs-orgs
                           (map :login)
                           set)
        vcs-rider-names (into #{}
                              (comp
                               (filter #(= vcs-type (:vcs_type %)))
                               (map :name))
                              piggieback-orgs)]
    [:div.controls
     [:h4 (str (utils/prettify-vcs_type vcs-type) " Organizations")]
     ;; orgs that this user can add to piggieback orgs and existing piggieback orgs
     (for [org-name (cond->> (disj (clojure.set/union vcs-org-names
                                                      vcs-rider-names)
                                   vcs-user-name)
                      true (sort-by string/lower-case)
                      vcs-user-name (cons vcs-user-name))]
       [:div.checkbox
        [:label
         [:input
          (let [checked? (contains? selected-orgs {:name org-name
                                                   :vcs_type vcs-type})]
            {:value org-name
             :checked checked?
             :on-change (fn [event]
                          (raise! owner [:selected-piggieback-orgs-updated {:org {:name org-name
                                                                                  :vcs_type vcs-type}
                                                                            :selected? (not checked?)}]))
             :type "checkbox"})]
         org-name]])]))

(defn piggieback-organizations [{{org-name :name
                                  org-vcs_type :vcs_type
                                  {piggieback-orgs :piggieback_org_maps} :plan
                                  :keys [selected-piggieback-orgs]} :current-org
                                 :keys [user-orgs]} owner]
  (reify
    om/IRender
    (render [_]
      (html
       (let [{gh-users-and-orgs "github"
              bb-users-and-orgs "bitbucket"}
             (->> user-orgs
                  (remove #(and (= (:login %) org-name)
                                (= (:vcs_type %) org-vcs_type)))
                  (group-by :vcs_type))]
         [:div
          [:fieldset
           [:p
            "Your plan covers all projects (including forked repos) in the "
            [:strong org-name]
            " organization by default."]
           [:p "You can let any GitHub or Bitbucket organization you belong to, including personal accounts, piggieback on your plan. Projects in your piggieback organizations will be able to run builds on your plan."]
           [:p "Members of the piggieback organizations will be able to see that you're paying for them, the name of your plan, and the number of containers you've paid for. They won't be able to edit the plan unless they are also admins on the " org-name " org."]
           (if-not (or gh-users-and-orgs bb-users-and-orgs)
             [:div "Loading organization list..."]
             [:form
              [:div.org-lists
               (when (seq gh-users-and-orgs)
                 (piggieback-org-list piggieback-orgs selected-piggieback-orgs gh-users-and-orgs owner))
               (when (seq bb-users-and-orgs)
                 (piggieback-org-list piggieback-orgs selected-piggieback-orgs bb-users-and-orgs owner))]
              [:div
               (button/managed-button
                {:success-text "Saved"
                 :loading-text "Saving..."
                 :failed-text "Failed"
                 :on-click #(raise! owner [:save-piggieback-orgs-clicked {:org-name org-name
                                                                          :vcs-type org-vcs_type
                                                                          :selected-piggieback-orgs selected-piggieback-orgs}])
                 :kind :primary}
                "Save")]])]])))))

(defn transfer-organizations-list [[{:keys [vcs_type]} :as users-and-orgs] selected-transfer-org owner]
  ;; split user-orgs from orgs and grab the first (and only) user-org
  ;; Note that user-login will be nil if the current org is the user-org
  (let [{[{user-login :login}] nil
         orgs true} (group-by :org users-and-orgs)
        sorted-org-names (cond->> orgs
                              true (map :login)
                              true (sort-by string/lower-case)
                              user-login (cons user-login))]
    [:div.controls
     [:h4 (str (utils/prettify-vcs_type vcs_type) " Organizations")]
     (for [org-name sorted-org-names
           :let [org-map {:org-name org-name
                          :vcs-type vcs_type}]]
       [:div.radio
        [:label {:name org-name}
         [:input {:value org-name
                  :checked (= selected-transfer-org
                              org-map)
                  :on-change #(raise! owner
                                      [:selected-transfer-org-updated
                                       {:org org-map}])
                  :type "radio"}]
         org-name]])]))

(defn transfer-organizations [{user-orgs :user-orgs
                               {org-name :name
                                :keys [vcs_type selected-transfer-org]} :current-org}
                              owner]
  (om/component
   (html
    (let [{gh-user-orgs "github"
           bb-user-orgs "bitbucket"} (->> user-orgs
                                          (remove #(and (= (:login %) org-name)
                                                        (= (:vcs_type %) vcs_type)))
                                          (group-by :vcs_type))
          selected-transfer-org-name (:org-name selected-transfer-org)]
      [:div
       [:fieldset
        [:div.alert.alert-warning
         [:p [:strong "Warning!"]]
         [:p "If you're not an admin on the "
          (if selected-transfer-org-name
            (str selected-transfer-org-name " organization,")
            "organization you transfer to,")
          " then you won't be able to transfer the plan back or edit the plan."]
         [:p
          "The transferred plan will be extended to include the "
          org-name " organization, so your builds will continue to run. Only admins of the "
          (if selected-transfer-org-name
            (str selected-transfer-org-name " org")
            "organization you transfer to")
          " will be able to edit the plan."]]
        (if-not user-orgs
          [:div "Loading organization list..."]
          [:form
           [:div.org-lists
            (when gh-user-orgs
              (transfer-organizations-list gh-user-orgs selected-transfer-org owner))
            (when bb-user-orgs
              (transfer-organizations-list bb-user-orgs selected-transfer-org owner))]
           [:div
            (button/managed-button
             {:success-text "Transferred"
              :loading-text "Transferring..."
              :disabled? (not selected-transfer-org)
              :kind :primary
              :on-click #(raise! owner
                                 [:transfer-plan-clicked
                                  {:from-org {:org-name org-name
                                              :vcs-type vcs_type}
                                   :to-org selected-transfer-org}])}
             "Transfer Plan")]])]]))))

(defn organizations [app owner]
  (om/component
   (html
    [:div.organizations
     [:legend "Share & Transfer"]
     (card/collection
       [(card/titled {:title "Share Plan"}
                     (om/build piggieback-organizations {:current-org (get-in app state/org-data-path)
                                                         :user-orgs (get-in app state/user-organizations-path)}))
        (card/titled {:title "Transfer Ownership"}
                     (om/build transfer-organizations {:current-org (get-in app state/org-data-path)
                                                       :user-orgs (get-in app state/user-organizations-path)}))])])))

(defn- billing-card [app owner]
  (reify
    om/IInitState
    (init-state [_]
      {:checkout-loaded? (stripe/checkout-loaded?)
       :checkout-loaded-chan (chan)})
    om/IWillMount
    (will-mount [_]
      (let [ch (om/get-state owner [:checkout-loaded-chan])
            checkout-loaded? (om/get-state owner [:checkout-loaded?])]
        (when-not checkout-loaded?
          (go (<! ch) ;; wait for success message
              (utils/mlog "Stripe checkout loaded")
              (om/set-state! owner [:checkout-loaded?] true))
          (utils/mlog "Loading Stripe checkout")
          (stripe/load-checkout ch))))
    om/IWillUnmount
    (will-unmount [_]
      (close! (om/get-state owner [:checkout-loaded-chan])))
    om/IRenderState
    (render-state [_ {:keys [checkout-loaded?]}]
      (let [card (get-in app state/stripe-card-path)]
        (if-not (and card checkout-loaded?)
          (card/titled {:title "Card on File"}
                       (spinner))
          (card/titled {:title "Card on File"
                        :action (button/managed-button
                                  {:success-text "Success"
                                   :failed-text "Failed"
                                   :loading-text "Updating"
                                   :on-click #(raise! owner [:update-card-clicked])
                                   :kind :primary
                                   :size :small}
                                  "Update Credit Card")}
                       (om/build table/table
                                 {:rows [card]
                                  :key-fn (constantly "card")
                                  :columns [{:header "Card holder email"
                                             :cell-fn #(stripe-email-key % "N/A")}
                                            {:header "Card type"
                                             :cell-fn #(:type % "N/A")}
                                            {:header "Card number"
                                             :cell-fn #(if (contains? % :last4)
                                                         (str "xxxx-xxxx-xxxx-" (:last4 %))
                                                         "N/A")}
                                            {:header "Expiry"
                                             :cell-fn #(if (contains? % :exp_month)
                                                         (gstring/format "%02d/%s" (:exp_month %) (:exp_year %))
                                                         "N/A")}]})))))))

;; Render a friendly human-readable version of a Stripe discount coupon.
;; Stripe has a convention for this that does not seem to be documented, so we
;; reverse engineer it here.
;; Examples from Stripe are:
;;     100% off for 1 month
;;     100% off for 6 months
;;  $100.00 off for 6 months
;;   $19.00 off for 12 months
;;      25% off forever
(defn format-discount
  [plan]
  (let [{ duration-in-months :duration_in_months
          percent-off        :percent_off
          amount-off         :amount_off
          duration           :duration
          id                 :id}  (get-in plan [:discount :coupon])
        discount-amount (if percent-off
                          (str percent-off "%")
                          (gstring/format "$%.2f" (/ amount-off 100)))
        discount-period (cond (= duration "forever") "forever"
                              (= duration-in-months 1) "for 1 month"
                              :else (gstring/format "for %d months" duration-in-months))]
    [:p (str "Your plan includes " discount-amount " off " discount-period " from coupon code ")
     [:strong id]]))

;; Show a 'Discount' section showing any Stripe discounts that are being appied
;; the current plan.
;; Important: If there are no discounts, we don't want to show anything;
;; we do not want to tempt happy, paying customers to search online for discount
;; codes.
(defn- billing-discounts [app owner]
  (reify
    om/IRender
    (render [_]
      (let [plan (get-in app state/org-plan-path)]
        (when (pm/has-active-discount? plan)
          (card/titled {:title "Discounts"}
                       (format-discount plan)))))))

(defn- billing-invoice-data [app owner]
  (reify
    om/IRender
    (render [_]
      (let [plan-data (get-in app state/org-plan-path)
            settings (state-utils/merge-inputs plan-data
                                               (inputs/get-inputs-from-app-state owner)
                                               [:billing_email :billing_name :extra_billing_data])]
        (if-not plan-data
          (card/titled {:title "Statement Information"}
                       (spinner))
          (card/titled {:title "Statement Information"
                        :action (button/managed-button {:kind :primary
                                                        :success-text "Saved Statement Info"
                                                        :loading-text "Saving Statement Info..."
                                                        :size :small
                                                        :on-click #(raise! owner [:save-invoice-data-clicked])}
                                                       "Save Statement Info")}

                       (html
                         [:div
                          [:div.form-group
                           [:p "Where would you like to receive your monthly statements?
                                             Add as many recipients as you like to the field below.
                                             Separate email addresses with a comma."]
                           (om/build form/text-field {:label "Statement email(s)"
                                                      :value (str (:billing_email settings))
                                                      :on-change #(utils/edit-input owner (conj state/inputs-path :billing_email) %)})]
                          [:div.form-group
                           [:p "What is the name of your organization?
                                             This name will appear on every statement."]
                           (om/build form/text-field {:label "Organization name"
                                                      :value (str (:billing_name settings))
                                                      :on-change #(utils/edit-input owner (conj state/inputs-path :billing_name) %)})]
                          [:div.form-group
                           [:p "Is there anything else we should include on your statement?
                                             Feel free to add any special information such as company address, PO#, or VAT ID."]
                           (om/build form/text-area {:label "Additional statement information"
                                                     :value (str (:extra_billing_data settings))
                                                     :on-change #(utils/edit-input owner (conj state/inputs-path :extra_billing_data) %)})]])))))))

(defn- invoice-total
  [invoice]
  (/ (:amount_due invoice) 100))

(defn- stripe-ts->date
  [ts]
  (datetime/year-month-day-date (* 1000 ts)))

(defn- ->balance-string [balance]
  (let [suffix (cond
                (< balance 0) " in credit."
                (> balance 0) " payment outstanding."
                :else "")
        amount (-> balance Math/abs (/ 100) .toLocaleString)]
    (str "$" amount suffix)))

(defn- billing-invoices [app owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/popover "#invoice-popover-hack"
                     {:animation false
                      :trigger "hover"
                      :html true})
      (utils/tooltip "#resend-invoice-tooltip-hack"
                     {:animation false}))
    om/IRender
    (render [_]
      (let [account-balance (get-in app state/org-plan-balance-path)
            invoices (get-in app state/org-invoices-path)]
        (if-not (and account-balance invoices)
          (card/titled {:title "Past Statements"}
                       (spinner))
          (card/titled {:title "Past Statements"}
                       (html
                         [:div.invoice-data
                          [:p.current-balance "Current account balance"
                           [:i.fa.fa-question-circle#invoice-popover-hack
                            {:title "Current account balance"
                             :data-content (str "<p>This is the credit you have with Circle. If your credit is positive, then we will use it before charging your credit card.</p>"
                                                "<p>Contact us if you'd like us to send you a refund for the balance.</p>"
                                                "<p>This amount may take a few hours to refresh.</p>")}]
                           [:span "- "(->balance-string account-balance)]]
                          (om/build table/table
                                    {:rows invoices
                                     :key-fn :id
                                     :columns [{:header "Invoice date"
                                                :cell-fn (comp stripe-ts->date :date)}
                                               {:header "Time period covered"
                                                :cell-fn (comp str stripe-ts->date :period_start)}
                                               {:header "Total"
                                                :type :right
                                                :cell-fn #(gstring/format "$%.2f" (invoice-total %))}
                                               {:type :shrink
                                                :cell-fn
                                                (fn [invoice]
                                                  (button/managed-button
                                                    {:failed-text "Failed" ,
                                                     :success-text "Sent" ,
                                                     :loading-text "Sending..." ,
                                                     :on-click #(raise! owner [:resend-invoice-clicked
                                                                               {:invoice-id (:id invoice)}])
                                                     :size :small
                                                     :kind :flat}
                                                    "Resend"))}]})])))))))

(defn billing [app owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         [:legend "Billing & Statements"]
         (maybe-show-error-banner! app owner)
         (card/collection
           [(om/build billing-card app)
            (om/build billing-invoice-data app)
            (om/build billing-discounts app)
            (om/build billing-invoices app)])]))))

(defn progress-bar [{:keys [max value]} owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:progress {:data-component `progress-bar
                   :value value
                   :max max}]))))


(defn osx-usage-table-plan [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [org-name (:org_name plan)
            osx-max-minutes (some-> plan :osx :template :max_minutes)
            osx-usage (-> plan :usage :os:osx)]
        (html
          [:div {:data-component `osx-usage-table}
           (let [osx-usage (->> osx-usage
                                ;Remove any entries that do not have keys matching :yyyy_mm_dd.
                                ;This is to filter out the old style of keys which were :yyyy_mm.
                                (filterv (comp (partial re-matches #"\d{4}_\d{2}_\d{2}") name key))

                                ;Filter returns a vector of vectors [[key value] [key value]] so we
                                ;need to put them back into a map with (into {})
                                (into {})

                                ;Sort by key, which also happends to be billing period start date.
                                (sort)

                                ;Reverse the order so the dates decend
                                (reverse)

                                ;All we care about are the last 12 billing periods
                                (take 12)

                                ;Finally feed in the plan's max minutes
                                (map (fn [[_ usage-map]]
                                       {:usage usage-map
                                        :max osx-max-minutes})))]
             (if (and (not-empty osx-usage) osx-max-minutes)
               [:div
                (let [rows (for [{:keys [usage max]} osx-usage
                                 :let [{:keys [amount from to]} usage
                                       amount (.round js/Math (/ amount 1000 60))
                                       percent (.round js/Math (* 100 (/ amount max)))]]
                             {:from from
                              :to to
                              :max max
                              :amount amount
                              :percent percent
                              :over-usage? (> amount max)})]
                  (om/build table/table
                            {:rows rows
                             :key-fn (comp hash (juxt :from :to))
                             :columns [{:header "Billing Period"
                                        :type :shrink
                                        :cell-fn #(html
                                                   [:span
                                                    (datetime/month-name-day-date (:from %))
                                                    " - "
                                                    (datetime/month-name-day-date (:to %))])}
                                       {:header "Usage"
                                        :cell-fn #(om/build progress-bar {:max (:max %) :value (:amount %)})}
                                       {:type #{:right :shrink}
                                        :cell-fn #(html
                                                   [:span (when (:over-usage? %) {:class "over-usage"})
                                                    (:percent %) "%"])}
                                       {:type #{:right :shrink}
                                        :cell-fn #(html
                                                   [:span (when (:over-usage? %) {:class "over-usage"})
                                                    (.toLocaleString (:amount %)) "/" (.toLocaleString (:max %)) " minutes"])}]}))]
               [:div.explanation
                [:p "Looks like you haven't run any builds yet."]]))])))))

(defn osx-plan-overview [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{{plan-org-name :name
              plan-vcs-type :vcs_type} :org}
            plan]
        (html
         [:div
           (if (pm/osx? plan)
             (let [plan-name (some-> plan :osx :template :name)]
               [:div.plan-header
                [:div.split-plan-block
                 ;FIXME: need to add conditional for you have no plan selected. select plan below.
                 [:h1
                  (cond
                    (pm/osx-trial-active? plan)
                    (gstring/format "You're currently on the CircleCI OS X trial and have %s left. " (pm/osx-trial-days-left plan))

                    (and (pm/osx-trial-plan? plan)
                         (not (pm/osx-trial-active? plan)))
                    [:span "Your free trial of CircleCI for OS X has expired. Please "
                     [:a {:href (routes/v1-org-settings-path {:org plan-org-name
                                                              :vcs_type plan-vcs-type
                                                              :_fragment "osx-pricing"})} "select a plan"]" to continue building!"]

                    :else
                    (gstring/format "Current OS X plan: %s - $%d/month " plan-name(pm/osx-cost plan)))]
                 [:div
                  [:p "You can update your OS X plan below."]
                  (when (and (pm/osx-trial-plan? plan) (not (pm/osx-trial-active? plan)))
                    [:p "The OS X trial you've selected has expired, please choose a plan below."])
                  (when (and (pm/osx-trial-plan? plan) (pm/osx-trial-active? plan))
                    [:p (gstring/format "You have %s left on the OS X trial." (pm/osx-trial-days-left plan))])]
                 [:p "Questions? Check out the FAQs below."]]
                [:div.split-plan-block
                 (om/build osx-usage-table-plan {:plan plan})]])
             [:div.plan-header
              [:h1 "No OS X plan selected"]
              [:p "Choose an OS X plan below."]])])))))

(defn osx-usage-table [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [org-name (:org_name plan)
            osx-max-minutes (some-> plan :osx :template :max_minutes)
            osx-usage (-> plan :usage :os:osx)]
        (html
          [:div.card {:data-component `osx-usage-table}
           [:div.header (str org-name "'s OS X usage")]
           [:hr.divider]
           (let [osx-usage (->> osx-usage
                                ;Remove any entries that do not have keys matching :yyyy_mm_dd.
                                ;This is to filter out the old style of keys which were :yyyy_mm.
                                (filterv (comp (partial re-matches #"\d{4}_\d{2}_\d{2}") name key))

                                ;Filter returns a vector of vectors [[key value] [key value]] so we
                                ;need to put them back into a map with (into {})
                                (into {})

                                ;Sort by key, which also happends to be billing period start date.
                                (sort)

                                ;Reverse the order so the dates decend
                                (reverse)

                                ;All we care about are the last 12 billing periods
                                (take 12)

                                ;Finally feed in the plan's max minutes
                                (map (fn [[_ usage-map]]
                                       {:usage usage-map
                                        :max osx-max-minutes})))]
             (if (and (not-empty osx-usage) osx-max-minutes)
               [:div
                (let [rows (for [{:keys [usage max]} osx-usage
                                 :let [{:keys [amount from to]} usage
                                       amount (.round js/Math (/ amount 1000 60))
                                       percent (.round js/Math (* 100 (/ amount max)))]]
                             {:from from
                              :to to
                              :max max
                              :amount amount
                              :percent percent
                              :over-usage? (> amount max)})]
                  (om/build table/table
                            {:rows rows
                             :key-fn (comp hash (juxt :from :to))
                             :columns [{:header "Billing Period"
                                        :type :shrink
                                        :cell-fn #(html
                                                   [:span
                                                    (datetime/month-name-day-date (:from %))
                                                    " - "
                                                    (datetime/month-name-day-date (:to %))])}
                                       {:header "Usage"
                                        :cell-fn #(om/build progress-bar {:max (:max %) :value (:amount %)})}
                                       {:type #{:right :shrink}
                                        :cell-fn #(html
                                                   [:span (when (:over-usage? %) {:class "over-usage"})
                                                    (:percent %) "%"])}
                                       {:type #{:right :shrink}
                                        :cell-fn #(html
                                                   [:span (when (:over-usage? %) {:class "over-usage"})
                                                    (.toLocaleString (:amount %)) "/" (.toLocaleString (:max %)) " minutes"])}]}))]
               [:div.explanation
                [:p "Looks like you haven't run any builds yet."]]))])))))

(defn osx-overview [{:keys [plan]} owner]
  (reify
    om/IRender
    (render [_]
      (let [{{plan-org-name :name
              plan-vcs-type :vcs_type} :org}
            plan]
        (html
         [:div
          (if (pm/osx? plan)
            (let [plan-name (some-> plan :osx :template :name)]
              [:div
               [:div
                [:h1
                 (cond
                   (pm/osx-trial-active? plan)
                   (gstring/format "You're currently on the CircleCI OS X trial and have %s left. " (pm/osx-trial-days-left plan))

                   (and (pm/osx-trial-plan? plan)
                        (not (pm/osx-trial-active? plan)))
                   [:span "Your free trial of CircleCI for OS X has expired. Please "
                    [:a {:href (routes/v1-org-settings-path {:org plan-org-name
                                                             :vcs_type plan-vcs-type
                                                             :_fragment "osx-pricing"})} "select a plan"]" to continue building!"]
                   :else
                   (gstring/format "Current OS X plan: %s - $%d/month " plan-name(pm/osx-cost plan)))]
                [:p
                 (if (pm/osx? plan)
                  [:strong [:a {:href (routes/v1-org-settings-path {:org plan-org-name
                                                                    :vcs_type plan-vcs-type
                                                                    :_fragment "osx-pricing"})} "Update OS X plan"]]
                  [:strong [:a {:href (routes/v1-org-settings-path {:org plan-org-name
                                                                    :vcs_type plan-vcs-type
                                                                    :_fragment "osx-pricing"})} "Choose OS X plan"]])]

                [:fieldset
                 (when (and (pm/osx-trial-plan? plan) (not (pm/osx-trial-active? plan)))
                   [:p "The OS X trial you've selected has expired, please choose a plan below."])
                 (when (and (pm/osx-trial-plan? plan) (pm/osx-trial-active? plan))
                   [:p (gstring/format "You have %s left on the OS X trial." (pm/osx-trial-days-left plan))])]]
               [:div
                (om/build osx-usage-table-plan {:plan plan})]])
            [:div
             [:h1 "No OS X plan selected"]
             [:p
              [:strong [:a {:href (routes/v1-org-settings-path {:org plan-org-name
                                                                :vcs_type plan-vcs-type
                                                                :_fragment "osx-pricing"})} "Choose an OS X plan"]]
              (when (pm/grandfathered? plan)
                [:p " Note: changes to your billing will update your entire account"
                      " (including Linux plans) off of our legacy plans to our current pricing model."])]])])))))

(defn github-marketplace-messaging []
  [:div "Current plan managed on "
   [:a {:href "https://github.com/marketplace/circleci"
        :target "_blank"}
    "GitHub Marketplace"]])

(defn displayed-linux-plan-info [{:keys [current-linux-cost containers trial-containers in-student-trial? github-marketplace-plan?]}]
  (if github-marketplace-plan?
    (github-marketplace-messaging)
    (gstring/format "Current Linux plan: %s (%s) - $%s/month"
                    (if in-student-trial?
                      "Student"
                      (gstring/format "%s%s"
                        (if (= current-linux-cost 0) "Hobbyist" "Paid")
                        (if (> trial-containers 0) " + Trial " " ")))
                    (plural-multiples containers "container")
                    current-linux-cost)))

(defn displayed-linux-paid-info [paid-linux-containers]
  (gstring/format "%s %s paid"
                  (plural-multiples paid-linux-containers "container")
                  (if (> paid-linux-containers 1) "are" "is")))

(defn displayed-linux-free-info [in-student-trial?]
  (gstring/format "%s free"
                  (if in-student-trial? "3 containers are" "1 container is")))

(defn linux-plan-overview [app owner]
  (om/component
   (html
    (let [org-name (get-in app state/org-name-path)
          vcs_type (get-in app state/org-vcs_type-path)
          {plan-org :org :as plan} (get-in app state/org-plan-path)
          plan-total (pm/stripe-cost plan)
          linux-container-cost (pm/linux-per-container-cost plan)
          price (-> plan :paid :template :price)
          paid-linux-containers (pm/paid-linux-containers plan)
          containers (pm/linux-containers plan)
          piggiebacked? (pm/piggieback? plan org-name vcs_type)
          current-linux-cost (pm/current-linux-cost plan)
          in-student-trial? (pm/in-student-trial? plan)
          trial-containers (pm/trial-containers plan)
          github-marketplace-plan? (pm/github-marketplace-plan? plan)]
      [:div.split-plan-block
       [:div.explanation
        (when piggiebacked?
          [:div.alert.alert-warning
           [:div.usage-message
            [:div.text
             [:span "This organization's projects will build under "
              [:a {:href (routes/v1-org-settings-path {:org (:name plan-org)
                                                       :vcs_type (:vcs_type plan-org)})}
               (:name plan-org) "'s plan."]]]]])
        (if (config/enterprise?)
          [:p "Your organization currently uses a maximum of " containers " containers. If your fleet size is larger than this, you should raise this to get access to your full capacity."]
          [:p
           [:h1 (displayed-linux-plan-info {:current-linux-cost current-linux-cost
                                            :containers containers
                                            :trial-containers trial-containers
                                            :in-student-trial? in-student-trial?
                                            :github-marketplace-plan? github-marketplace-plan?})]
           (when-not github-marketplace-plan?
             (if (> current-linux-cost 0)
               [:div
                (displayed-linux-paid-info paid-linux-containers)
                (if piggiebacked? ". "
                                  (list ", at $" (pm/current-linux-cost plan) "/month. "))
                (if (pm/grandfathered? plan)
                  (list "We've changed our pricing model since this plan began, so its current price "
                    "is grandfathered in. "
                    "It would be $" (pm/linux-cost plan (pm/linux-containers plan)) " at current prices. "
                    "We'll switch it to the new model if you upgrade or downgrade. ")
                  (list
                    (when (and (pm/freemium? plan) (> containers 1))
                      [:span (displayed-linux-free-info in-student-trial?)])
                    [:br]
                    ;; make sure to link to the add-containers page of the plan's org,
                    ;; in case of piggiebacking.
                    (when-not piggiebacked?
                      [:p "You can update your Linux plan below."])))]
               [:div
                (gstring/format "%s. %s." (displayed-linux-paid-info paid-linux-containers) (displayed-linux-free-info in-student-trial?))
                [:p
                 [:strong "Add more containers to update your plan below"]
                 " and gain access to concurrent builds, parallelism, engineering support, insights, build timings, and other cool stuff."]]))
           [:p "Questions? Check out the FAQs below."]])
        (when (and (> (pm/trial-containers plan) 0)
                   (not in-student-trial?))
          [:p
           (str trial-containers " of these are provided by a trial. They'll be around for "
                (pluralize (pm/days-left-in-trial plan) "more day")
                ".")])

        (when (config/enterprise?)
          (om/build linux-plan {:app app}))]]))))

(defn overview [app owner]
  (om/component
   (html
    (let [org-name (get-in app state/org-name-path)
          vcs_type (get-in app state/org-vcs_type-path)
          {plan-org :org :as plan} (get-in app state/org-plan-path)
          plan-total (pm/stripe-cost plan)
          linux-container-cost (pm/linux-per-container-cost plan)
          price (-> plan :paid :template :price)
          paid-linux-containers (pm/paid-linux-containers plan)
          containers (pm/linux-containers plan)
          piggiebacked? (pm/piggieback? plan org-name vcs_type)
          current-linux-cost (pm/current-linux-cost plan)   ; why linux-container-cost then current-linux-cost?
          in-student-trial? (pm/in-student-trial? plan)
          github-marketplace-plan? (pm/github-marketplace-plan? plan)
          trial-containers (pm/trial-containers plan)]
      [:div.overview-cards-container
       [:legend "Plan Overview"]
       (when piggiebacked?
          [:div.alert.alert-warning
           [:div.usage-message
            [:div.text
             [:span "This organization's projects will build under "
              [:a {:href (routes/v1-org-settings-path {:org (:name plan-org)
                                                       :vcs_type (:vcs_type plan-org)})}
               (:name plan-org) "'s plan."]]]]])
       [:div.overview-cards
        (card/collection
          [(card/titled {:title (html [:span [:i.fa.fa-linux.title-icon] "Linux Plan Overview"])}
             (html
               [:div.plan-overview.linux-plan-overview
                (if (config/enterprise?)
                  [:p "Your organization currently uses a maximum of " containers " containers. If your fleet size is larger than this, you should raise this to get access to your full capacity."]

                  [:div
                   [:h1 (displayed-linux-plan-info {:current-linux-cost current-linux-cost
                                                    :containers containers
                                                    :trial-containers trial-containers
                                                    :in-student-trial? in-student-trial?
                                                    :github-marketplace-plan? github-marketplace-plan?})]
                   (when (and (= current-linux-cost 0)
                              (not piggiebacked?)
                              (not github-marketplace-plan?))
                     [:div
                      [:p (gstring/format "%s. %s." (displayed-linux-paid-info paid-linux-containers) (displayed-linux-free-info in-student-trial?))]
                      [:p
                       [:strong
                        [:a {:href (routes/v1-org-settings-path {:org (:name plan-org)
                                                                 :vcs_type (:vcs_type plan-org)
                                                                 :_fragment "linux-pricing"})}
                         "Add more containers to update your plan"]]
                       " and gain access to concurrent builds, parallelism, engineering support, insights, build timings, and other cool stuff."]])])
                (when (and (> trial-containers 0)
                           (not github-marketplace-plan?)
                           (not in-student-trial?))
                  [:p
                   (str trial-containers " of these are provided by a trial. They'll be around for "
                        (pluralize (pm/days-left-in-trial plan) "more day")
                        ".")])
                (when (and (not (config/enterprise?))
                           (not github-marketplace-plan?)
                           (pm/linux? plan))
                  [:p
                   (str (pm/paid-linux-containers plan) " of these are paid")
                   (if piggiebacked? ". "
                       (list ", at $" (pm/current-linux-cost plan) "/month. "))

                   (when (and (pm/freemium? plan) (> containers 1))
                     [:span (displayed-linux-free-info in-student-trial?) "."])
                   [:br]
                   (if (pm/grandfathered? plan)
                     (list "We've changed our pricing model since this plan began, so its current price "
                           "is grandfathered in. "
                           "It would be $" (pm/linux-cost plan (pm/linux-containers plan)) " at current prices. "
                           "We'll switch it to the new model if you upgrade or downgrade. ")
                     (list
                      ;; make sure to link to the add-containers page of the plan's org,
                      ;; in case of piggiebacking.
                      [:p
                        [:strong
                         [:a {:href (routes/v1-org-settings-path {:org (:name plan-org)
                                                                  :vcs_type (:vcs_type plan-org)
                                                                  :_fragment "linux-pricing"})}
                          "Add more containers to update your plan"]]
                        " for more parallelism and shorter queue times."]))])
                (when (config/enterprise?)
                  (om/build linux-plan {:app app}))]))
           (card/titled {:title (html [:span [:i.fa.fa-apple.title-icon] "OS X Plan Overview"])}
                        (html
                          [:div.plan-overview.macos-plan-overview
                           (om/build osx-overview {:plan plan})]))])]]))))

(defn main-component []
  (merge
    {:overview overview
     :users users
     :projects projects}
    (when (feature/enabled? :contexts-v1)
      {:contexts contexts/main})
    (if (config/enterprise?)
      {:containers overview}
      {:containers cloud-pricing
       :osx-pricing cloud-pricing
       :linux-pricing cloud-pricing
       :organizations organizations
       :billing billing})))

(defn org-settings [app owner]
  (reify
    om/IRender
    (render [_]
      (let [org-data (get-in app state/org-data-path)
            user-org-admin? (user/org-admin-authorized? (get-in app state/user-path) (get-in app state/selected-org-path))
            vcs_type (:vcs_type org-data)
            subpage (or (get-in app state/org-settings-subpage-path)
                        :overview)
            plan (get-in app state/org-plan-path)]
        (html [:div.org-page
               (if-not (:loaded org-data)
                 (spinner)
                 [:div
                  (when (pm/suspended? plan)
                    (om/build project-common/suspended-notice {:plan plan :vcs_type vcs_type}))
                  (om/build common/flashes (get-in app state/error-message-path))
                  [:div#subpage
                   [:div
                    (if (or user-org-admin?
                            (:admin plan))
                      (om/build (get (main-component) subpage projects) app)
                      [:div (om/build non-admin-plan
                                      {:login (get-in app [:current-user :login])
                                       :org-name (get-in app state/org-settings-org-name-path)
                                       :vcs_type (get-in app state/org-settings-vcs-type-path)
                                       :subpage subpage})])]]])])))))
