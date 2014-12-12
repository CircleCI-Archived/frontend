(ns frontend.components.project-settings
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [raise!]]
            [clojure.string :as string]
            [frontend.models.build :as build-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.project :as project-model]
            [frontend.models.user :as user-model]
            [frontend.components.account :as account]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.components.inputs :as inputs]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.html :refer [hiccup->html-str]]
            [frontend.utils.state :as state-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [goog.string :as gstring]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(def sidebar
  [:ul.side-list
   [:li.side-title "Project Settings"]
   [:li [:a {:href "edit"} "Overview"]]
   [:li.side-title "Tweaks"]
   [:li [:a {:href "#parallel-builds"} "Parallelism"]]
   [:li [:a {:href "#env-vars"} "Environment variables"]]
   [:li [:a {:href "#experimental"} "Experimental Settings"]]
   [:li.side-title "Test Commands"]
   [:li [:a {:href "#setup"} "Dependencies"]]
   [:li [:a {:href "#tests"} "Tests"]]
   [:li.side-title "Notifications"]
   [:li [:a {:href "#hooks"} "Chatrooms"]]
   [:li [:a {:href "#webhooks"} "Webhooks"]]
   [:li [:a {:href "#badges"} "Status Badges"]]
   [:li.side-title "Permissions"]
   [:li [:a {:href "#checkout"} "Checkout SSH keys"]]
   [:li [:a {:href "#ssh"} "SSH keys"]]
   [:li [:a {:href "#api"} "API tokens"]]
   [:li [:a {:href "#aws"} "AWS keys"]]
   [:li.side-title "Continuous Deployment"]
   [:li [:a {:href "#heroku"} "Heroku"]]
   [:li [:a {:href "#aws-codedeploy"} "AWS CodeDeploy"]]
   [:li [:a {:href "#deployment"} "Other Deployments"]]])

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
             :on-click #(do (raise! owner [channel-message (merge {:project-id project-id} channel-args)])
                            false)
             :data-loading-text "Starting..."
             :data-success-text "Started..."
             :type "submit"}])])))))

(defn overview [project-data owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.project-settings-block
        [:h2 "How to configure " (vcs-url/project-name (get-in project-data [:project :vcs_url]))]
        [:ul.overview-options
         [:li.overview-item
          [:h4 "Option 1"]
          [:p
           "Do nothing! Circle infers many settings automatically. Works great for Ruby, Python, NodeJS, Java and Clojure. However, if it needs tweaks or doesn't work, see below."]]
         [:li.overview-item
          [:h4 "Option 2"]
          [:p
           "Override inferred settings and add new test commands "
           [:a {:href "#setup"} "through the web UI"]
           ". This works great for prototyping changes."]]
         [:li.overview-item
          [:h4 "Option 3"]
          [:p
           "Override all settings via a "
           [:a {:href "/docs/configuration"} "circle.yml file"]
           " in your repo. Very powerful."]]]]))))

(defn mini-parallelism-faq [project-data]
  [:div.mini-faq
   [:div.mini-faq-item
    [:h3 "What are containers?"]
    [:p
     "Containers are what we call the virtual machines that your tests run in. Your current plan has "
     (get-in project-data [:plan :containers])
     " containers and supports up to "
     (plan-model/max-parallelism (:plan project-data))
     "x paralellism."]

    [:p "With 16 containers you could run:"]
    [:ul
     [:li "16 simultaneous builds at 1x parallelism"]
     [:li "8 simultaneous builds at 2x parallelism"]
     [:li "4 simultaneous builds at 4x parallelism"]
     [:li "2 simultaneous builds at 8x parallelism"]
     [:li "1 build at 16x parallelism"]]]
   [:div.mini-faq-item
    [:h3 "What is parallelism?"]
    [:p
     "We split your tests into groups, and run each group on different machines in parallel. This allows them run in a fraction of the time, for example:"]
    [:p]
    [:ul
     [:li "a 45 minute build fell to 18 minutes with 3x build speed,"]
     [:li
      "a 20 minute build dropped to 11 minutes with 2x build speed."]]
    [:p
     "Each machine is completely separated (sandboxed and firewalled) from the others, so that your tests can't conflict with each other: separate databases, file systems, process space, and memory."]
    [:p
     "For RSpec, Cucumber and Test::Unit, we'll automatically run your tests, splitting them appropriately among different machines. If you have a different test suite, you can "
     [:a
      {:href "/docs/parallel-manual-setup"}
      "control the parallelism directly"]
     "."]]
   [:div.mini-faq-item
    [:h3 "What do others think?"]
    [:blockquote
     [:i
      "The thing that sold us on Circle was the speed. Their tests run really really fast. We've never seen that before. One of our developers just pushes to branches so that Circle will run his tests, instead of testing on his laptop. The parallelization just works - we didn't have to tweak anything. Amazing service."]]
    [:ul
     [:li [:a {:href "http://zencoder.com/company/"} "Brandon Arbini"]]
     [:li [:a {:href "http://zencoder.com/"} "Zencoder.com"]]]]])

(defn parallel-label-classes [{:keys [plan project] :as project-data} parallelism]
  (concat
   []
   (when (> parallelism (project-model/max-selectable-parallelism plan project)) ["disabled"])
   (when (= parallelism (get-in project-data [:project :parallel])) ["selected"])
   (when (not= 0 (mod (project-model/usable-containers plan project) parallelism)) ["bad_choice"])))

(defn parallelism-tile
  "Determines what we show when they hover over the parallelism option"
  [project-data parallelism]
  (let [plan (:plan project-data)
        project (:project project-data)
        project-id (project-model/id project)]
    (list
     [:div.parallelism-upgrades
      (if-not (plan-model/in-trial? plan)
        (cond (> parallelism (plan-model/max-parallelism plan))
              [:div.insufficient-plan
               "Your plan only allows up to "
               (plan-model/max-parallelism plan) "x parallelism."
               [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                           :subpage "plan"})}
                "Upgrade"]]

              (> parallelism (project-model/max-selectable-parallelism plan project))
              [:div.insufficient-containers
               "Not enough containers available."
               [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                           :subpage "containers"})}
                "Add More"]])
        (when (> parallelism (project-model/max-selectable-parallelism plan project))
          [:div.insufficient-trial
           "Trials only come with " (plan-model/trial-containers plan) " available containers."
               [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                           :subpage "plan"})}
                "Add a plan"]]))]

     ;; Tell them to upgrade when they're using more parallelism than their plan allows,
     ;; but only on the tiles between (allowed parallelism and their current parallelism]
     (when (and (> (:parallel project) (project-model/usable-containers plan project))
                (>= (:parallel project) parallelism)
                (> parallelism (project-model/usable-containers plan project)))
       [:div.insufficient-minimum
        "Unsupported. Upgrade or lower parallelism."
        [:i.fa.fa-question-circle {:title (str "You need " parallelism " containers on your plan to use "
                                               parallelism "x parallelism.")}]
        [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                    :subpage "containers"})}
         "Upgrade"]]))))

(defn parallelism-picker [project-data owner]
  [:div.parallelism-picker
   (if-not (:plan project-data)
     [:div.loading-spinner common/spinner]
     (let [plan (:plan project-data)
           project (:project project-data)
           project-id (project-model/id project)]
       (list
        (when (:parallelism-edited project-data)
          [:div.try-out-build
           (om/build branch-picker project-data {:opts {:button-text (str "Try a build!")}})])
        [:form.parallelism-items
         (for [parallelism (range 1 (max (plan-model/max-parallelism plan)
                                         (inc 24)))]
           [:label {:class (parallel-label-classes project-data parallelism)
                    :for (str "parallel_input_" parallelism)}
            parallelism
            (parallelism-tile project-data parallelism)
            [:input {:id (str "parallel_input_" parallelism)
                     :type "radio"
                     :name "parallel"
                     :value parallelism
                     :on-click #(raise! owner [:selected-project-parallelism
                                               {:project-id project-id
                                                :parallelism parallelism}])
                     :disabled (> parallelism (project-model/max-selectable-parallelism plan project))
                     :checked (= parallelism (:parallel project))}]])])))])

(defn parallel-builds [project-data owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div
        [:h2 (str "Change parallelism for " (vcs-url/project-name (get-in project-data [:project :vcs_url])))]
        (if-not (:plan project-data)
          [:div.loading-spinner common/spinner]
          (list (parallelism-picker project-data owner)
                (mini-parallelism-faq project-data)))]))))

(defn env-vars [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            inputs (inputs/get-inputs-from-app-state owner)
            new-env-var-name (:new-env-var-name inputs)
            new-env-var-value (:new-env-var-value inputs)
            project-id (project-model/id project)]
        (html
         [:div.environment-variables
          [:h2 "Environment variables for " (vcs-url/project-name (:vcs_url project))]
          [:div.environment-variables-inner
           [:p
            "Add environment variables to the project build.  You can add sensitive data (e.g. API keys) here, rather than placing them in the repository. "
            "The values can be any bash expression and can reference other variables, such as setting "
            [:code "M2_MAVEN"] " to " [:code "${HOME}/.m2)"] "."
            " To disable string substitution you need to escape the " [:code "$"]
            " characters by prefixing them with " [:code "\\"] "."
            " For example, a crypt'ed password like " [:code "$1$O3JMY.Tw$AdLnLjQ/5jXF9.MTp3gHv/"]
            " would be entered as " [:code "\\$1\\$O3JMY.Tw\\$AdLnLjQ/5jXF9.MTp3gHv/"] "."]
           [:form
            [:input#env-var-name
             {:required true, :type "text", :value new-env-var-name
              :on-change #(utils/edit-input owner (conj state/inputs-path :new-env-var-name) %)}]
            [:label {:placeholder "Name"}]
            [:input#env-var-value
             {:required true, :type "text", :value new-env-var-value
              :on-change #(utils/edit-input owner (conj state/inputs-path :new-env-var-value) %)}]
            [:label {:placeholder "Value"}]
            (forms/managed-button
             [:input {:data-failed-text "Failed",
                      :data-success-text "Added",
                      :data-loading-text "Adding...",
                      :value "Save variables",
                      :type "submit"
                      :on-click #(do
                                   (raise! owner [:created-env-var {:project-id project-id}])
                                   false)}])]
           (when-let [env-vars (seq (:envvars project-data))]
             [:table
              [:thead [:tr [:th "Name"] [:th "Value"] [:th]]]
              [:tbody
               (for [{:keys [name value]} env-vars]
                 [:tr
                  [:td {:title name} name]
                  [:td {:title value} value]
                  [:td
                   [:a
                    {:title "Remove this variable?",
                     :on-click #(raise! owner [:deleted-env-var {:project-id project-id
                                                                 :env-var-name name}])}
                    [:i.fa.fa-times-circle]
                    [:span " Remove"]]]])]])]])))))

(defn experiments [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))
            ;; This project's feature flags
            feature-flags (:feature_flags project)
            describe-flag (fn [{:keys [flag title blurb]}]
                            (when (contains? (set (keys feature-flags)) flag)
                              [:li
                               [:h4 title]
                               [:p blurb]
                               [:form
                                [:ul
                                 [:li
                                  [:label
                                   [:input.radio
                                    {:type "checkbox"
                                     :checked (get feature-flags flag)
                                     :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                               :flag flag
                                                                                               :value true}])}]
                                   " On"]]
                                 [:li
                                  [:label
                                   [:input.radio
                                    {:type "checkbox"
                                     :checked (not (get feature-flags flag))
                                     :on-change #(raise! owner [:project-feature-flag-checked {:project-id project-id
                                                                                               :flag flag
                                                                                               :value false}])}]
                                   " Off"]]]]]))]
        (html
         [:div.project-settings-block
          [:h2 "Experimental Settings"]
          [:p
           " We've got a few settings you can play with, to enable things we're working on. We'd love to "
           [:a {:on-click #(raise! owner [:project-experiments-feedback-clicked])}
            "know what you think about them"] "."
           " These " [:em "are"] " works-in-progress, though, and there may be some sharp edges. Be careful!"]
          [:ul
           (describe-flag {:flag :junit
                           :title "JUnit support"
                           :blurb [:p
                                   "We've been experimenting with better ways to display and manage "
                                   "test result data, especially for large test suites. This adds flags "
                                   "to some of our inferred commands to collect structured test output supplied by "
                                   "JUnit-compatible test runners. It currently works with RSpec and Cucumber if "
                                   "you're using our inferred test steps. For RSpec, we also require our fork of the "
                                   "rspec_junit_formatters gem. The line you need to add to your Gemfile is: "
                                   [:p [:code "gem 'rspec_junit_formatter', :git => 'git@github.com:circleci/rspec_junit_formatter.git'"]]
                                   "If you're using parallelism, we'll "
                                   "automatically use the timing data to give you better test splits. You'll also be able to "
                                   "fetch the test data via our API at https://circleci.com/api/v1/project/:org-name/:repo-name/:build-num/tests"]})
           (describe-flag {:flag :set-github-status
                           :title "GitHub Status updates"
                           :blurb [:p
                                   "By default, we update the status of every pushed commit with "
                                   "GitHub's status API. If you'd like to turn this off (if, for example, "
                                   "this is conflicting with another service), you can do so below."]})
           (describe-flag {:flag :oss
                           :title "Free and Open Source"
                           :blurb [:p
                                   "Be part of our F/OSS beta! Organizations now have three free containers"
                                   "reserved for F/OSS projects; enabling this will allow this project's "
                                   "builds to use them and let others see your builds, both through the "
                                   "web UI and the API."]})
           (describe-flag {:flag :build-fork-prs
                           :title "Project fork pull requests"
                           :blurb '([:p
                                     "CircleCI will automatically update the commit status shown on GitHub's "
                                     "pull request page. Builds will be run using the parent repository's plan "
                                     "and will be able to access the parent project's environment settings."]
                                    [:p
                                     "If you have SSH keys or AWS credentials stored in your project settings and "
                                     "untrusted forks can make pull requests against your repo, then this option "
                                     "isn't for you!"])})]])))))

(defn dependencies [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            inputs (inputs/get-inputs-from-app-state owner)
            settings (state-utils/merge-inputs project inputs [:setup :dependencies :post_dependencies])]
        (html
         [:div.dependencies-page
          [:h2 "Install dependencies for " (vcs-url/project-name (:vcs_url project))]
          [:p
           "You can also set your dependencies commands from your "
           [:a {:href "/docs/configuration#dependencies"} "circle.yml"] ". "
           "Note that anyone who can see this project on GitHub will be able to see these in your build pages. "
           "Don't put any secrets here that you wouldn't check in! Use our "
           [:a {:href "#env-vars"} "environment variables settings page"]
           " instead."]
          [:div.dependencies-inner
           [:form.spec_form
            [:fieldset
             [:textarea {:name "setup",
                         :required true
                         :value (str (:setup settings))
                         :on-change #(utils/edit-input owner (conj state/inputs-path :setup) % owner)}]
             [:label {:placeholder "Pre-dependency commands"}]
             [:p "Run extra commands before the normal setup, these run before our inferred commands. All commands are arbitrary bash statements, and run on Ubuntu 12.04. Use this to install and setup unusual services, such as specific DNS provisions, connections to a private services, etc."]
             [:textarea {:name "dependencies",
                         :required true
                         :value (str (:dependencies settings))
                         :on-change #(utils/edit-input owner (conj state/inputs-path :dependencies) %)}]
             [:label {:placeholder "Dependency overrides"}]
             [:p "Replace our inferred setup commands with your own bash commands. Dependency overrides run instead of our inferred commands for dependency installation. If our inferred commands are not to your liking, replace them here. Use this to override the specific pre-test commands we run, such as "
              [:code "bundle install"] ", " [:code "rvm use"] ", " [:code "ant build"] ", "
              [:code "configure"] ", " [:code "make"] ", etc."]
             [:textarea {:required true
                         :value (str (:post_dependencies settings))
                         :on-change #(utils/edit-input owner (conj state/inputs-path :post_dependencies) %)}]
             [:label {:placeholder "Post-dependency commands"}]
             [:p "Run extra commands after the normal setup, these run after our inferred commands for dependency installation. Use this to run commands that rely on the installed dependencies."]
             (forms/managed-button
              [:input {:value "Next, setup your tests",
                       :type "submit"
                       :data-loading-text "Saving..."
                       :on-click #(do (raise! owner [:saved-dependencies-commands {:project-id project-id}])
                                      false)}])]]]])))))

(defn tests [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            inputs (inputs/get-inputs-from-app-state owner)
            settings (state-utils/merge-inputs project inputs [:test :extra])]
        (html
         [:div.tests-page
          [:h2 "Set up tests for " (vcs-url/project-name (:vcs_url project))]
          [:p
           "You can also set your test commands from your "
           [:a {:href "/docs/configuration#dependencies"} "circle.yml"] ". "
           "Note that anyone who can see this project on GitHub will be able to see these in your build pages. "
           "Don't put any secrets here that you wouldn't check in! Use our "
           [:a {:href "#env-vars"} "environment variables settings page"]
           " instead."]
          [:div.tests-inner
           [:fieldset.spec_form
            [:textarea {:name "test",
                        :required true
                        :value (str (:test settings))
                        :on-change #(utils/edit-input owner (conj state/inputs-path :test) %)}]
            [:label {:placeholder "Test commands"}]
            [:p "Replace our inferred test commands with your own inferred commands. These test commands run instead of our inferred test commands. If our inferred commands are not to your liking, replace them here. As usual, all commands are arbitrary bash, and run on Ubuntu 12.04."]
            [:textarea {:name "extra",
                        :required true
                        :value (str (:extra settings))
                        :on-change #(utils/edit-input owner (conj state/inputs-path :extra) %)}]
            [:label {:placeholder "Post-test commands"}]
            [:p "Run extra test commands after the others finish. Extra test commands run after our inferred commands. Add extra tests that we haven't thought of yet."]
            (forms/managed-button
             [:input {:name "save",
                      :data-loading-text "Saving...",
                      :value "Save commands",
                      :type "submit"
                      :on-click #(do (raise! owner [:saved-test-commands {:project-id project-id}])
                                     false)}])
            [:div.try-out-build
             (om/build branch-picker
                       project-data
                       {:opts {:button-text "Save & Go!"
                               :channel-message :saved-test-commands
                               :channel-args {:project-id project-id :start-build? true}}})]]]])))))

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
                                     :title "Only send notifications for builds that fail or fix the tests. Otherwise, send a notification for every build."}]])))))

(defn chatroom-item [project-id settings owner
                     {:keys [service doc inputs show-fixed-failed? top-section-content settings-keys]}]
  (let [service-id (string/lower-case service)]
    [:div {:class (str "chat-room-item " service-id)}
     [:div.chat-room-head [:h4 {:class (str "chat-i-" service-id)} service]]
     [:div.chat-room-body
      [:section
       doc
       top-section-content
       (when show-fixed-failed?
         (om/build fixed-failed-input {:settings settings :field (keyword (str service-id "_notify_prefs"))}))]
      [:section
       (for [{:keys [field placeholder]} inputs]
         (list
          [:input {:id (string/replace (name field) "_" "-") :required true :type "text"
                   :value (str (get settings field))
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
             "& Test Hook"])])]]]))

(defn chatrooms [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            inputs (inputs/get-inputs-from-app-state owner)
            settings (state-utils/merge-inputs project inputs project-model/notification-keys)]
        (html
         [:div
          [:h2 "Chatroom setup for " (vcs-url/project-name (:vcs_url project))]
          [:div.chat-rooms
           (for [chat-spec [{:service "Hipchat"
                             :doc (list [:p "To get your API token, create a \"notification\" token via the "
                                         [:a {:href "https://hipchat.com/admin/api"} "HipChat site"] "."]
                                        [:label ;; hipchat is a special flower
                                         {:for "hipchat-notify"}
                                         [:input#hipchat-notify
                                          {:type "checkbox"
                                           :checked (:hipchat_notify settings)
                                           ;; n.b. can't use inputs-state b/c react won't changed
                                           ;;      checked state without a rerender
                                           :on-change #(utils/edit-input owner (conj state/project-path :hipchat_notify) % :value (not (:hipchat_notify settings)))}]
                                         [:span "Show popups"]])
                             :inputs [{:field :hipchat_room :placeholder "Room"}
                                      {:field :hipchat_api_token :placeholder "API"}]
                             :show-fixed-failed? true
                             :settings-keys project-model/hipchat-keys}

                            {:service "Campfire"
                             :doc [:p "To get your API token, visit your company Campfire, then click \"My info\". Note that if you use your personal API token, campfire won't show the notifications to you!"]
                             :inputs [{:field :campfire_room :placeholder "Room"}
                                      {:field :campfire_subdomain :placeholder "Subdomain"}
                                      {:field :campfire_token :placeholder "API"}]
                             :show-fixed-failed? true
                             :settings-keys project-model/campfire-keys}

                            {:service "Flowdock"
                             :doc [:p "To get your API token, visit your Flowdock, then click the \"Settings\" icon on the left. On the settings tab, click \"Team Inbox\""]
                             :inputs [{:field :flowdock_api_token :placeholder "API"}]
                             :show-fixed-failed? false
                             :settings-keys project-model/flowdock-keys}

                            {:service "IRC"
                             :doc nil
                             :inputs [{:field :irc_server :placeholder "Hostname"}
                                      {:field :irc_channel :placeholder "Channel"}
                                      {:field :irc_keyword :placeholder "Private Keyword"}
                                      {:field :irc_username :placeholder "Username"}
                                      {:field :irc_password :placeholder "Password (optional)"}]
                             :show-fixed-failed? true
                             :settings-keys project-model/irc-keys}

                            {:service "Slack"
                             :doc [:p "To get your Webhook URL, visit Slack's "
                                   [:a {:href "https://my.slack.com/services/new/circleci"}
                                    "CircleCI Integration"]
                                   " page, choose a default channel, and click the green \"Add CircleCI Integration\" button at the bottom of the page."]
                             :inputs [{:field :slack_webhook_url :placeholder "Webhook URL"}]
                             :show-fixed-failed? true
                             :settings-keys project-model/slack-keys}

                            {:service "Hall"
                             :doc [:p "To get your Room / Group API token, go to "
                                   [:strong "Settings > Integrations > CircleCI"]
                                   " from within your Hall Group."]
                             :inputs [{:field :hall_room_api_token :placeholder "API"}]
                             :show-fixed-failed? true
                             :settings-keys project-model/hall-keys}]]
             (chatroom-item project-id settings owner chat-spec))]])))))

(defn webhooks [project-data owner]
  (om/component
   (html
    [:div
     [:h2 "Webhooks for " (vcs-url/project-name (get-in project-data [:project :vcs_url]))]
     [:div.doc
      [:p
       "Circle also support webhooks, which run at the end of a build. They can be configured in your "
       [:a {:href "https://circleci.com/docs/configuration#notify" :target "_blank"}
        "circle.yml file"] "."]]])))

(def status-styles
  {"badge" {:label "Badge" :string ".png?style=badge"}
   "shield" {:label "Shield" :string ".svg?style=shield"}
   "flat" {:label "Flat" :string ".svg?style=flat"}
   "flat-square" {:label "FlatSquare" :string ".svg?style=flat-square"}
   "svg" {:label "Badge" :string ".svg?style=svg"}})

(def status-formats
  {"image" {:label "Image URL"
            :template :image}
   "markdown" {:label "Markdown"
               :template #(str "[![Circle CI](" (:image %) ")](" (:target %) ")")}
   "textile" {:label "Textile"
              :template #(str "!" (:image %) "!:" (:target %))}
   "rdoc" {:label "Rdoc"
           :template #(str "{<img src=\"" (:image %) "\" alt=\"Circle CI\" />}[" (:target %) "]")}
   "asciidoc" {:label "AsciiDoc"
               :template #(str "image:" (:image %) "[\"Circle CI\", link=\"" (:target %) "\"]")}
   "rst" {:label "reStructuredText"
          :template #(str ".. image:: " (:image %) "\n    :target: " (:target %))}
   "pod" {:label "pod"
          :template #(str "=for HTML <a href=\"" (:target %) "\"><img src=\"" (:image %) "\"></a>")}})

(defn status-badges [project-data owner]
  (let [project (:project project-data)
        oss (get-in project [:feature_flags :oss])
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
        gh-path (if (seq branch) (str project-name "/tree/" (gstring/urlEncode branch)) project-name)
        target (str (.. js/window -location -origin) "/gh/" gh-path)
        style (get-in project-data [:status-badges :style] "svg")
        image (str target (get-in status-styles [style :string]))
        image (if (seq token) (str image "&circle-token=" token) image)
        format (get-in project-data [:status-badges :format] "markdown")
        code ((:template (status-formats format)) {:image image :target target})]
    (om/component
     (html
      [:div.status-page
       [:h2 "Status badges for " project-name]
       [:div "Use this tool to easily create embeddable status badges. Perfect for your project's README or wiki!"]
       [:div.status-page-inner
        [:form

         [:div.branch
          [:h4 "Branch"]
          [:div.styled-select
           [:select {:value branch
                     :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :branch) %)}
            [:option {:value ""} "Default"]
            [:option {:disabled "disabled"} "-----"]
            (for [branch branches]
              [:option {:value branch} branch])]
           [:i.fa.fa-chevron-down]]]

         [:div.token
          [:h4 "API Token"]
          (when-not (or oss (seq token))
            [:p [:span.warning "Warning: "] "Private projects require an " [:a {:href "#api"} "API token"] "."])
          [:div.styled-select
           [:select {:value token
                     :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :token) %)}
            [:option {:value ""} "None"]
            [:option {:disabled "disabled"} "-----"]
            (for [{:keys [token label]} tokens]
              [:option {:value token} label])]
           [:i.fa.fa-chevron-down]]]

         #_ ;; Hide style selector until "badge" style is improved. See PR #3140 discussion.
         [:div.style
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
          [:div.styled-select
           [:select {:value format
                     :on-change #(utils/edit-input owner (conj state/project-data-path :status-badges :format) %)}
            (for [[id {:keys [label]}] status-formats]
              [:option {:value id} label])]
           [:i.fa.fa-chevron-down]]
          [:textarea {:readonly true
                      :value code
                      :on-click #(.select (.-target %))}]]

         ]]]))))

(defn ssh-keys [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            {:keys [hostname private-key]
             :or {hostname "" private-key ""}} (:new-ssh-key project-data)]
        (html
         [:div.sshkeys-page
          [:h2 "SSH keys for " (vcs-url/project-name (:vcs_url project))]
          [:div.sshkeys-inner
           [:p "Add keys to the build VMs that you need to deploy to your machines. If the hostname field is blank, the key will be used for all hosts."]
           [:form
            [:input#hostname {:required true, :type "text" :value (str hostname)
                              :on-change #(utils/edit-input owner (conj state/project-data-path :new-ssh-key :hostname) %)}]
            [:label {:placeholder "Hostname"}]
            [:textarea#privateKey {:required true :value (str private-key)
                                   :on-change #(utils/edit-input owner (conj state/project-data-path :new-ssh-key :private-key) %)}]
            [:label {:placeholder "Private Key"}]
            (forms/managed-button
             [:input#submit.btn
              {:data-failed-text "Failed",
               :data-success-text "Saved",
               :data-loading-text "Saving..",
               :value "Submit",
               :type "submit"
               :on-click #(do (raise! owner [:saved-ssh-key {:project-id project-id
                                                             :ssh-key {:hostname hostname
                                                                       :private_key private-key}}])
                              false)}])]
           (when-let [ssh-keys (seq (:ssh_keys project))]
             [:table
              [:thead [:tr [:th "Hostname"] [:th "Fingerprint"] [:th]]]
              [:tbody
               (for [{:keys [hostname fingerprint]} ssh-keys]
                 [:tr
                  [:td hostname]
                  [:td fingerprint]
                  [:td [:a {:title "Remove this Key?",
                            :on-click #(raise! owner [:deleted-ssh-key {:project-id project-id
                                                                        :hostname hostname
                                                                        :fingerprint fingerprint}])}
                        [:i.fa.fa-times-circle]
                        [:span " Remove"]]]])]])]])))))

(defn checkout-key-link [key project user]
  (cond (= "deploy-key" (:type key))
        (str "https://github.com/" (vcs-url/project-name (:vcs_url project)) "/settings/keys")

        (and (= "github-user-key" (:type key)) (= (:login key) (:login user)))
        "https://github.com/settings/ssh"

        :else nil))

(defn checkout-key-description [key project]
  (condp = (:type key)
    "deploy-key" (str (vcs-url/project-name (:vcs_url project)) " deploy key")
    "github-user-key" (str (:login key) " user key")
    nil))

(defn checkout-ssh-keys [data owner]
  (reify
    om/IRender
    (render [_]
      (let [project-data (:project-data data)
            user (:user data)
            project (:project project-data)
            project-id (project-model/id project)
            project-name (vcs-url/project-name (:vcs_url project))
            checkout-keys (:checkout-keys project-data)]
        (html
         [:div.checkout-page
          [:h2 "Checkout keys for " project-name]
          [:div.checkout-page-inner
           (if (nil? checkout-keys)
             [:div.loading-spinner common/spinner]

             [:div
              (if-not (seq checkout-keys)
                [:p "No checkout key is currently configured! We won't be able to check out your project for testing :("]
                [:div
                 [:p
                  "Here are the keys we can currently use to check out your project, submodules, "
                  "and private GitHub dependencies. The currently preferred key is highlighted, but "
                  "we will automatically fall back to the other keys if the preferred key is revoked."]
                 [:table
                  [:thead [:th "Description"] [:th "Fingerprint"] [:th]]
                  [:tbody
                   (for [checkout-key checkout-keys
                         :let [fingerprint (:fingerprint checkout-key)
                               github-link (checkout-key-link checkout-key project user)]]
                     [:tr {:class (when (:preferred checkout-key) "preferred")}
                      [:td
                       (if github-link
                         [:a.slideBtn {:href github-link :target "_blank"}
                          (checkout-key-description checkout-key project) " " [:i.fa.fa-github]]

                         (checkout-key-description checkout-key project))]
                      [:td fingerprint]
                      [:td
                       [:a.slideBtn
                        {:title "Remove this key?",
                         :on-click #(raise! owner [:delete-checkout-key-clicked {:project-id project-id
                                                                                 :project-name project-name
                                                                                 :fingerprint fingerprint}])}
                        [:i.fa.fa-times-circle] " Remove"]]])]]])
              (when-not (seq (filter #(= "deploy-key" (:type %)) checkout-keys))
                [:div.add-key
                 [:h4 "Add deploy key"]
                 [:p
                  "Deploy keys are the best option for most projects: they only have access to a single GitHub repository."]
                 [:div.request-user
                  (forms/managed-button
                   [:input.btn
                    {:type "submit"
                     :on-click #(do (raise! owner
                                          [:new-checkout-key-clicked {:project-id project-id
                                                                      :project-name project-name
                                                                      :key-type "deploy-key"}])
                                    false)
                     :title "Create a new deploy key, with access only to this project."
                     :value (str "Create and add " project-name " deploy key")
                     :data-loading-text "Saving..."
                     :data-success-text "Saved"}])]])
              (when-not (some #{"github-user-key"} (map :type checkout-keys))
                [:div.add-key
                 [:h4 "Add user key"]
                 [:p
                  "If a deploy key can't access all of your project's private dependencies, we can configure it to use an SSH key with the same level of access to GitHub repositories that you have."]
                 [:div.authorization
                  (if-not (user-model/public-key-scope? user)
                    (list
                     [:p "In order to do so, you'll need to grant authorization from GitHub to the \"admin:public_key\" scope. This will allow us to add a new authorized public key to your GitHub account. (Feel free to drop this additional scope after we've added the key!)"]
                     [:a.btn.ghu-authorize
                      {:href (gh-utils/auth-url :scope ["admin:public_key" "user:email" "repo"])
                       :title "Grant admin:public_key authorization so that we can add a new SSH key to your GitHub account"}
                      "Authorize w/ GitHub " [:i.fa.fa-github]])

                    [:div.request-user
                     (forms/managed-button
                      [:input.btn
                       {:tooltip "{ title: 'Create a new user key for this project, with access to all of the projects of your GitHub account.', animation: false }"
                        :type "submit"
                        :on-click #(do (raise! owner [:new-checkout-key-clicked {:project-id project-id
                                                                                 :project-name project-name
                                                                                 :key-type "github-user-key"}])
                                       false)
                        :value (str "Create and add " (:login user) " user key" )
                        :data-loading-text "Saving..."
                        :data-success-text "Saved"}])])]])

              [:div.help-block
               [:h2 "About checkout keys"]
               [:h4 "What is a deploy key?"]
               [:p "A deploy key is a repo-specific SSH key. GitHub has the public key, and we store the private key. Possession of the private key gives read/write access to a single repository."]
               [:h4 "What is a user key?"]
               [:p "A user key is a user-specific SSH key. GitHub has the public key, and we store the private key. Possession of the private key gives the ability to act as that user, for purposes of 'git' access to repositories."]
               [:h4 "How are these keys used?"]
               [:p "When we build your project, we install the private key into the .ssh directory, and configure ssh to use it when communicating with 'github.com'. Therefore, it gets used for:"]
               [:ul
                [:li "checking out the main project"]
                [:li "checking out any GitHub-hosted submodules"]
                [:li "checking out any GitHub-hosted private dependencies"]
                [:li "automatic git merging/tagging/etc."]]
               [:p]
               [:p "That's why a deploy key isn't sufficiently powerful for projects with additional private dependencies!"]
               [:h4 "What about security?"]
               [:p "The private keys of the checkout keypairs we generate never leave our systems (only the public key is transmitted to GitHub), and are safely encrypted in storage. However, since they are installed into your build containers, any code that you run in Circle can read them. You shouldn't push untrusted code to Circle!"]
               [:h4 "Isn't there a middle ground between deploy keys and user keys?"]
               [:p "Not really :("]
               [:p "Deploy keys and user keys are the only key types that GitHub supports. Deploy keys are globally unique (i.e. there's no way to make a deploy key with access to multiple repositories) and user keys have no notion of \\scope\\ separate from the user they're associated with."]
               [:p "Your best bet, for fine-grained access to more than one repo, is to create what GitHub calls a "
                [:a {:href "https://help.github.com/articles/managing-deploy-keys#machine-users"} "machine user"]
                ". Give this user exactly the permissions your build requires, then log into CircleCI as the machine user and associate its user key with your project."]]])]])))))

(defn scope-popover-html []
  ;; nb that this is a bad idea in general, but should be ok for rarely used popovers
  (hiccup->html-str
   [:div
    [:p "A token's scope limits what can be done with it."]

    [:h5 "Status"]
    [:p
     "Allows read-only access to the build status (passing, failing, etc) of any branch of the project. Its intended use is "
     [:a {:target "_blank" :href "/docs/status-badges"} "sharing status badges"]
     " and "
     [:a {:target "_blank", :href "/docs/polling-project-status"} "status polling tools"]
     " for private projects."]

    [:h5 "Build Artifacts"]
    [:p "Allows read-only access to build artifacts of any branch of the project. Its intended use is for serving files to deployment systems."]

    [:h5 "All"]
    [:p "Allows full read-write access to this project in CircleCI. It is intended for full-fledged API clients which only need to access a single project."]

    ]))

(defn api-tokens [project-data owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (utils/popover "#scope-popover-hack" {:html true
                                            :delay 0
                                            :animation false
                                            :placement "left"
                                            :title "Scope"
                                            :content (scope-popover-html)}))
    om/IRender
    (render [_]
      (let [project (:project project-data)
            project-id (project-model/id project)
            {:keys [scope label]
             :or {scope "status" label ""}} (:new-api-token project-data)]
        (html
         [:div.circle-api-page
          [:h2 "API tokens for " (vcs-url/project-name (:vcs_url project))]
          [:div.circle-api-page-inner
           [:p "Create and revoke project-specific API tokens to access this project's details using our API. First choose a scope "
            [:i.fa.fa-question-circle#scope-popover-hack {:title "Scope"}]
            " and then create a label."]
           [:form
            [:div.styled-select
             [:select {:name "scope" :value scope
                       :on-change #(utils/edit-input owner (conj state/project-data-path :new-api-token :scope) %)}
              [:option {:value "status"} "Status"]
              [:option {:value "view-builds"} "Build Artifacts"]
              [:option {:value "all"} "All"]]
             [:i.fa.fa-chevron-down]]
            [:input
             {:required true, :type "text" :value (str label)
              :on-change #(utils/edit-input owner (conj state/project-data-path :new-api-token :label) %)}]
            [:label {:placeholder "Token label"}]
            (forms/managed-button
             [:input
              {:data-failed-text "Failed",
               :data-success-text "Created",
               :data-loading-text "Creating...",
               :on-click #(do (raise! owner [:saved-project-api-token {:project-id project-id
                                                                       :api-token {:scope scope
                                                                                   :label label}}])
                              false)
               :value "Create token",
               :type "submit"}])]
           (when-let [tokens (seq (:tokens project-data))]
             [:table
              [:thead
               [:th "Scope"]
               [:th "Label"]
               [:th "Token"]
               [:th "Created"]
               [:th]]
              [:tbody
               (for [{:keys [scope label token time]} tokens]
                 [:tr
                  [:td scope]
                  [:td label]
                  [:td [:span.code token]]
                  [:td time]
                  [:td
                   [:a.slideBtn
                    {:title "Remove this Key?",
                     :on-click #(raise! owner [:deleted-project-api-token {:project-id project-id
                                                                           :token token}])}
                    [:i.fa.fa-times-circle]
                    [:span " Remove"]]]])]])]])))))

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
         [:div.heroku-api
          [:h2 "Set personal Heroku API key for " (vcs-url/project-name (:vcs_url project))]
          [:div.heroku-step
           [:h4 "Step 1: Heroku API key"]
           [:div (when (:heroku_api_key user)
                   [:p "Your Heroku key is entered. Great!"])
            [:p (:heroku_api_key user)]
            [:div (when-not (:heroku_api_key user)
                    (om/build account/heroku-key {:current-user user} {:opts {:project-page? true}}))]
            [:div (when (:heroku_api_key user)
                    [:p
                     "You can edit your Heroku key from your "
                     [:a {:href "/account/heroku"} "account page"] "."])]]]
          [:div.heroku-step
           [:h4 "Step 2: Associate a Heroku SSH key with your account"]
           [:span "Current deploy user: "
            [:strong (or (:heroku_deploy_user project) "none") " "]
            [:i.fa.fa-question-circle
             {:data-bind "tooltip: {}",
              :title "This will affect all deploys on this project. Skipping this step will result in permission denied errors when deploying."}]]
           [:form.api
            (if (= (:heroku_deploy_user project) (:login user))
              (forms/managed-button
               [:input.remove-user
                {:data-success-text "Saved",
                 :data-loading-text "Saving...",
                 :on-click #(do (raise! owner [:removed-heroku-deploy-user {:project-id project-id}])
                                false)
                 :value "Remove Heroku Deploy User",
                 :type "submit"}])

              (forms/managed-button
               [:input.set-user
                {:data-success-text "Saved",
                 :data-loading-text "Saving...",
                 :on-click #(do (raise! owner [:set-heroku-deploy-user {:project-id project-id
                                                                        :login login}])
                                false)
                 :value (str "Set user to " (:login user)),
                 :type "submit"}]))]]
          [:div.heroku-step
           [:h4
            "Step 3: Add deployment settings to your "
            [:a {:href "/docs/configuration#deployment"} "circle.yml file"] " (example below)."]
           [:pre
            [:code
             "deployment:\n"
             "  staging:\n"
             "    branch: master\n"
             "    heroku:\n"
             "      appname: foo-bar-123"]]]])))))

(defn other-deployment [project-data owner]
  (om/component
   (html
    [:div
     [:h2
      "Other deployments for " (vcs-url/project-name (get-in project-data [:project :vcs_url]))]
     [:div.doc
      [:p "Circle supports deploying to any server, using custom commands. See "
       [:a {:target "_blank",
            :href "https://circleci.com/docs/configuration#deployment"}
        "our deployment documentation"]
       " to set it up."]]])))

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
          [:div
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
             {:required true, :type "text", :value (or access_key_id "")
              :on-change #(utils/edit-input owner (input-path :access_key_id) %)}]
            [:label {:placeholder "Access Key ID"}]

            [:input#secret-access-key
             {:required true, :type "text", :value (or secret_access_key "")
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
                        :on-click #(do
                                     (raise! owner [:saved-project-settings {:project-id project-id :merge-paths [[:aws :keypair]]}])
                                     false)}])
              (when (and access_key_id secret_access_key)
               (forms/managed-button
                [:input.remove {:data-failed-text "Failed"
                                :data-success-text "Cleared"
                                :data-loading-text "Clearing..."
                                :value "Clear AWS keys"
                                :type "submit"
                                :on-click #(do
                                           (raise! owner [:edited-input {:path (input-path) :value nil}])
                                           (raise! owner [:saved-project-settings {:project-id project-id}])
                                           false)}]))]]])))))

(defn aws [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)]
        (html
         [:div.aws-page
          [:h2 "AWS keys for " (vcs-url/project-name (:vcs_url project))]
          [:div.aws-page-inner
            (om/build aws-keys-form project-data)]])))))


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
                   :on-click #(do
                               (raise! owner [:new-codedeploy-app-name-entered])
                               false)}]]]))))

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
                        :content (hiccup->html-str [:p "The directory in your repo to package up into an application revision. "
                                                    "This is relative to your repo's root, " [:code "/"] " means the repo's root "
                                                    "directory, " [:code "/app"] " means the app directory in your repo's root "
                                                    "directory."])})
        (utils/popover "#bucket-popover-hack"
                       {:html true :delay 0 :animation false
                        :placement "right" :title "Revision Location: Bucket Name"
                        :content (hiccup->html-str [:p "The name of the S3 bucket CircleCI should store application revisions for \"" (name app-name) "\" in."])})
        (utils/popover "#key-pattern-popover-hack"
                       {:html true :delay 0 :animation false
                        :placement "right" :title "Revision Location: Key Pattern"
                        :content (hiccup->html-str [:p "A template used to construct S3 keys for storing application revisions."
                                           "You can use " [:a {:href "/docs/continuous-deployment-with-aws-codedeploy#key-patterns"} "substitution variables"]
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
           [:div.styled-select
            [:select {:class (when (not aws-region) "placeholder")
                      :value (or aws-region "")
                      ;; Updates the project cursor in order to trigger a re-render
                      :on-change #(utils/edit-input owner (conj state/project-path :aws :services :codedeploy app-name :region) %)}
             [:option {:value ""} "Choose AWS Region..."]
             [:option {:disabled "disabled"} "-----"]
             [:option {:value "us-east-1"} "us-east-1"]
             [:option {:value "us-west-2"} "us-west-2"]]
            [:i.fa.fa-chevron-down]]

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
                                                                              :merge-paths [[:aws :services :codedeploy]]}])
                                      false)}])
           (forms/managed-button
            [:input.remove {:data-failed-text "Failed",
                            :data-success-text "Removed",
                            :data-loading-text "Removing...",
                            :value "Remove app",
                            :type "submit"
                            :on-click #(do
                                         (raise! owner [:edited-input {:path (input-path) :value nil}])
                                         (raise! owner [:saved-project-settings {:project-id project-id}])
                                         false)}])]])))))

(defn aws-codedeploy [project-data owner]
  (reify
    om/IRender
    (render [_]
      (let [project (:project project-data)
            applications (get-in project [:aws :services :codedeploy])
            app-name (some-> applications first key)]
        (html
         [:div.aws-codedeploy
          [:h2 "CodeDeploy application settings for " (vcs-url/project-name (:vcs_url project))]
          [:p "CodeDeploy is an AWS service for deploying to your EC2 instances. "
              "Check out our " [:a {:href "/docs/continuous-deployment-with-aws-codedeploy"} "getting started with CodeDeploy"]
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
                 "block in your " [:a {:href "/docs/configuration#deployment"} "circle.yml file"] ". "
                 "If you skip this step you will need to add all deployment settings into your circle.yml file."]
             (if (not (seq applications))
               ;; No settings set, need to get the application name first
               (om/build aws-codedeploy-app-name project-data)
               ;; Once we have an application name we can accept the rest of the settings
               (om/build aws-codedeploy-app-details project-data))]
            [:div.aws-codedeploy-step
             [:h4 "Step 3"]
             [:p "Add deployment settings to your "
                 [:a {:href "/docs/configuration#deployment"} "circle.yml file"]
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
                      "        deployment_group: my-deployment-group\n"))]]]]])))))

(defn follow-sidebar [project owner]
  (reify
    om/IRender
    (render [_]
      (let [project-id (project-model/id project)
            vcs-url (:vcs_url project)]
        (html
         [:div.follow-status
          [:div.followed
           ;; this is weird, but it's what the css expectss
           (when (:followed project)
             (list
              [:i.fa.fa-group]
              [:h4 "You're following this repo"]
              [:p
               "We'll keep an eye on this and update you with personalized build emails. "
               "You can stop these any time from your "
               [:a {:href "/account"} "account settings"]
               "."]
              (forms/managed-button
               [:button {:on-click #(raise! owner [:unfollowed-project {:vcs-url vcs-url
                                                                        :project-id project-id}])
                         :data-loading-text "Unfollowing..."}
                "Unfollow"])))]
          [:div.not-followed
           (when-not (:followed project)
             (list
              [:h4 "You're not following this repo"]
              [:p
               "We can't update you with personalized build emails unless you follow this project. "
               "Projects are only tested if they have a follower."]
              (forms/managed-button
               [:button {:on-click #(raise! owner [:followed-project {:vcs-url vcs-url
                                                                      :project-id project-id}])
                         :data-loading-text "Following..."}
                "Follow"])))]])))))

(defn project-settings [data owner]
  (reify
    om/IRender
    (render [_]
      (let [project-data (get-in data state/project-data-path)
            user (:current-user data)
            subpage (:project-settings-subpage data)]
        (html
         (if-not (get-in project-data [:project :vcs_url]) ; wait for project-settings to load
           [:div.loading-spinner common/spinner]
           [:div#project-settings
            [:aside sidebar]
            [:div.project-settings-inner {:style {:overflow (if (= :aws-codedeploy subpage) "visible" "auto")}}
             (om/build common/flashes (get-in data state/error-message-path))
             [:div#subpage
              (condp = subpage
                :parallel-builds (om/build parallel-builds project-data)
                :env-vars (om/build env-vars project-data)
                :experimental (om/build experiments project-data)
                :setup (om/build dependencies project-data)
                :tests (om/build tests project-data)
                :hooks (om/build chatrooms project-data)
                :webhooks (om/build webhooks project-data)
                :badges (om/build status-badges project-data)
                :ssh (om/build ssh-keys project-data)
                :checkout (om/build checkout-ssh-keys {:project-data project-data :user user})
                :api (om/build api-tokens project-data)
                :heroku (om/build heroku {:project-data project-data :user user})
                :deployment (om/build other-deployment project-data)
                :aws (om/build aws project-data)
                :aws-codedeploy (om/build aws-codedeploy project-data)
                (om/build overview project-data))]]
            (om/build follow-sidebar (:project project-data))]))))))
