(ns frontend.components.build-head
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as string]
            [frontend.async :refer [raise!]]
            [frontend.datetime :as datetime]
            [frontend.models.build :as build-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.test :as test-model]
            [frontend.components.builds-table :as builds-table]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.config :refer [intercom-enabled? github-endpoint env enterprise?]]
            [frontend.routes :as routes]
            [frontend.timer :as timer]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.visualization.build :as viz-build]
            [goog.string :as gstring]
            [goog.string.format]
            [inflections.core :refer (pluralize)]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

;; This is awful, can't we just pass build-head the whole app state?
;; splitting it up this way means special purpose paths to find stuff
;; in it depending on what sub-state with special keys we have, right?
(defn has-scope [scope data]
  (scope (:scopes data)))

(defn show-additional-containers-offer? [plan build]
  (when (and plan build (not (enterprise?)))
    (let [usage-queued-ms (build-model/usage-queued-time build)
          run-queued-ms (build-model/run-queued-time build)]
      ;; more than 10 seconds waiting for other builds, and
      ;; less than 10 seconds waiting for additional containers (our fault)
      (< run-queued-ms 10000 usage-queued-ms))))

(defn additional-containers-offer [plan build]
  [:p#additional_containers_offer
   "Too much waiting? You can " [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                                            :subpage "containers"})}
                                 "add more containers"]
   " and finish even faster."])

(defn new-additional-containers-offer [plan build]
  (let [run-phrase (if (build-model/finished? build)
                     "ran"
                     "is running")]
    [:div.additional-containers-offer
     [:p (str "This build " run-phrase " under " (:org_name plan) "'s plan which provides " (plan-model/usable-containers plan)
              " containers, plus 3 additional containers available for free and open source projects.")]
     [:p [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                     :subpage "containers"})}
          [:button "Add Containers"]] "to run more builds concurrently."]]))

(defn build-queue [data owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [build builds]} data
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            plan (:plan data)]
        (html
         (if-not builds
           [:div.loading-spinner common/spinner]
           [:div.build-queue.active
            (when (and (:queued_at build) (not usage-queued?))
              [:p "Circle " (when run-queued? "has") " spent "
               (om/build common/updating-duration {:start (:queued_at build)
                                                   :stop (or (:start_time build) (:stop_time build))})
               " acquiring containers for this build."])
            (when (< 10000 (build-model/run-queued-time build))
              [:p#circle_queued_explanation
               "We're sorry; this is our fault. Typically you should only see this when load spikes overwhelm our auto-scaling; waiting to acquire containers should be brief and infrequent."])

            (when (seq builds)
              (list
               [:p "This build " (if usage-queued? "has been" "was")
                " queued behind the following builds for "
                (om/build common/updating-duration {:start (:usage_queued_at build)
                                                    :stop (or (:queued_at build) (:stop_time build))})]

               (om/build builds-table/builds-table builds {:opts {:show-actions? true}})))
            (when (show-additional-containers-offer? plan build)
              (if (om/get-shared owner [:ab-tests :new_usage_queued_upsell])
                (new-additional-containers-offer plan build)
                (additional-containers-offer plan build)))]))))))

(defn linkify [text]
  (let [url-pattern #"(?im)(\b(https?|ftp)://[-A-Za-z0-9+@#/%?=~_|!:,.;]*[-A-Za-z0-9+@#/%=~_|])"
        pseudo-url-pattern #"(?im)(^|[^/])(www\.[\S]+(\b|$))"]
    (-> text
        ;; TODO: switch to clojure.string/replace when they fix
        ;; http://dev.clojure.org/jira/browse/CLJS-485...
        (.replace (js/RegExp. (.-source url-pattern) "gim")
                  "<a href=\"$1\" target=\"_blank\">$1</a>")
        (.replace (js/RegExp. (.-source pseudo-url-pattern) "gim")
                  "$1<a href=\"http://$2\" target=\"_blank\">$2</a>"))))

(defn maybe-project-linkify [text project-name]
  (if-not project-name
    text
    (let [issue-pattern #"(^|\s)#(\d+)\b"]
      (-> text
          (string/replace issue-pattern
                          (gstring/format "$1<a href='%s/%s/issues/$2' target='_blank'>#$2</a>" (gh-utils/http-endpoint) project-name))))))

(defn commit-line [{:keys [build subject body commit_url commit] :as commit-details} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when (seq body)
        (utils/tooltip (str "#commit-line-tooltip-hack-" commit) {:placement "bottom" :animation false})))
    om/IRender
    (render [_]
      (html
       [:div
        [:span {:title body
                :id (str "commit-line-tooltip-hack-" commit)
                :dangerouslySetInnerHTML {:__html (-> subject
                                                      (gstring/htmlEscape)
                                                      (linkify)
                                                      (maybe-project-linkify (vcs-url/project-name (:vcs_url build))))}}]
        [:a.sha-one {:href commit_url
                     :title commit}
         " "
         (subs commit 0 7)
         " "
         [:i.fa.fa-github]]]))))

(defn build-commits [build-data owner]
  (reify
    om/IRender
    (render [_]
      (let [build (:build build-data)
            build-id (build-model/id build)]
        (html
         [:div.build-commits-container
          [:div.build-commits-title
           (when (:compare build)
             [:a {:href (:compare build)}
              "compare "
              [:i.fa.fa-github]
              " "])
           (when (< 3 (count (:all_commit_details build)))
             [:a {:role "button"
                  :on-click #(raise! owner [:show-all-commits-toggled {:build-id build-id}])}
              (str (- (count (:all_commit_details build)) 3) " more ")
              (if (:show-all-commits build-data)
                [:i.fa.fa-caret-up]
                [:i.fa.fa-caret-down])])]
          (when (:subject build)
            [:div.build-commits-list
             (if-not (seq (:all_commit_details build))
               (om/build commit-line {:build build
                                      :subject (:subject build)
                                      :body (:body build)
                                      :commit_url (build-model/github-commit-url build)
                                      :commit (:vcs_revision build)})
               (list
                (om/build-all commit-line (take 3 (map #(assoc % :build build)
                                                       (:all_commit_details build))))
                (when (:show-all-commits build-data)
                  (om/build-all commit-line (drop 3 (map #(assoc % :build build)
                                                         (:all_commit_details build)))))))])])))))

(defn ssh-enabled-note
  "Note that SSH has been enabled for the build, with list of users"
  [[current-user? someone-else?] owner]
  (reify
    om/IRender
    (render [_]
      (html [:p (->> [(when current-user? "you")
                      (when someone-else? "someone other than you")]
                     (filter identity)
                     (string/join " and ")
                     (#(str % " enabled SSH for this build."))
                     (string/capitalize))]))))

(defn ssh-buttons
  "Show the enable-SSH button(s) for the SSH tab

  Includes the button to SSH to the current build if it is still running, and
  the current user hasn't already enabled SSH for themselves; always shows the
  button to rebuild with SSH enabled (for the current user.)

  Assumes that the user has permission to SSH to the build (=> these should be
  shown)"
  [build owner]
  (reify
    om/IRender
    (render [_]
      (let [build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
          [:div
           (if-not (build-model/finished? build)
             (forms/managed-button
               [:button.ssh_build
                {:data-loading-text "Adding your SSH keys..."
                 :title "Enable SSH for this build"
                 :on-click #(raise! owner [:ssh-current-build-clicked build-info])}
                "Enable SSH for this build"])
             (forms/managed-button
               [:button.ssh_build
                {:data-loading-text "Starting SSH build..."
                 :title "Retry with SSH in VM"
                 :on-click #(raise! owner [:ssh-build-clicked build-info])}
                "Retry this build with SSH enabled"]))])))))

(defn ssh-ad
  "Note about why you might want to SSH into a build and buttons to do so"
  [build owner]
    [:div.ssh-ad
     [:p "Often the best way to troubleshoot problems is to ssh into a running or finished build to look at log files, running processes, and so on."]
     (om/build ssh-buttons build)
     [:p "This will grant you ssh access to the build's containers, prevent the deploy step from starting, and keep the build up for 30 minutes after it finishes to give you time to investigate."]
     [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "ssh-build"})} "in our docs"] "."]])

(defn ssh-instructions
  "Instructions for SSHing into a build that you can SSH into"
  [build owner]
  (let [nodes (:node build)]
    (html
      [:div.ssh-info-container
       [:div.build-ssh-title
        [:p "You can SSH into this build. Use the same SSH public key that you use for GitHub. SSH boxes will stay up for 30 minutes."]
        [:p "This build takes up one of your concurrent builds, so cancel it when you are done."]]
       [:div.build-ssh-list
        [:dl.dl-horizontal
         (map (fn [node i]
                (list
                  [:dt (when (< 1 (count nodes)) [:span (str "container " i " ")])]
                  [:dd {:class (when (:ssh_enabled node) "connected")}
                   [:span (gstring/format "ssh -p %s %s@%s " (:port node) (:username node) (:public_ip_addr node))]
                   (when-not (:ssh_enabled node)
                     [:span.loading-spinner common/spinner])]))
              nodes (range))]]
       [:div.build-ssh-doc
        "Debugging Selenium browser tests? "
        [:a {:href "/docs/browser-debugging#interact-with-the-browser-over-vnc"}
         "Read our doc on interacting with the browser over VNC"]
        "."]])))

(defn build-ssh [{:keys [build user]} owner]
  (reify
    om/IRender
    (render [_]
      (let [for-current-user? (build-model/current-user-ssh? build user)
            for-someone-else? (build-model/someone-else-ssh? build user)]
        (html
          [:div
           (when (seq (:ssh_users build))
             (om/build ssh-enabled-note [for-current-user? for-someone-else?]))
           (if for-current-user?
             (cond
               (build-model/ssh-enabled-now? build) (ssh-instructions build owner)
               (build-model/finished? build) (ssh-ad build owner))
             (ssh-ad build owner))])))))

(defn build-time-visualization [build owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (-> js/console (.log "did-mount calling get-node"))
      (let [el (om/get-node owner)]
        (viz-build/visualize-timing! el build)))
    om/IRender
    (render [_]
      (js/console.log "build-time-visualization render called")
      (html
       [:div.build-time-visualization]))))

(defn cleanup-artifact-path [path]
  (-> path
      (string/replace "$CIRCLE_ARTIFACTS/" "")
      (gstring/truncateMiddle 80)))

(defn artifacts-ad []
  [:div
   [:p "We didn't find any build artifacts for this build. You can upload build artifacts by moving files to the $CIRCLE_ARTIFACTS directory."]
   [:p "Use artifacts for screenshots, coverage reports, deployment tarballs, and more."]
   [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "build-artifacts"})} "in our docs"] "."]])

(defn artifacts-tree [prefix artifacts]
  (->> (for [artifact artifacts
             :let [parts (concat [prefix]
                                 (-> (:pretty_path artifact)
                                     (string/split #"/")))]]
         [(vec (remove #{""} parts)) artifact])
       (reduce (fn [acc [parts artifact]]
                 (let [loc (interleave (repeat :children) parts)]
                   (assoc-in acc (concat loc [:artifact]) artifact)))
               {})
       :children))

(defn artifacts-node [{:keys [artifacts show-artifact-links?] :as data} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (when (seq artifacts)
         [:ul.build-artifacts-list
          (map-indexed
           (fn node-entry [idx [part {:keys [artifact children]}]]
             (let [directory? (not artifact)
                   text       (if directory?
                                (str part "/")
                                part)
                   url        (:url artifact)
                   tag        (if (and url show-artifact-links?)
                                [:a.artifact-link {:href (:url artifact) :target "_blank"} text]
                                [:span.artifact-directory-text text])
                   key        (keyword (str "index-" idx))
                   closed?    (or
                               (:ancestors-closed? opts)
                               (om/get-state owner [key :closed?]))
                   toggler    (fn [event]
                                (let [key (keyword (str "index-" idx))]
                                  (.preventDefault event)
                                  (.stopPropagation event)
                                  (om/update-state! owner [key :closed?] not)))]
               [:li.build-artifacts-node
                (if directory?
                  [:div.build-artifacts-toggle-children
                   {:style    {:cursor  "pointer"
                               :display "inline"}
                    :on-click toggler}
                   (if closed? "▸  " "▾  ") tag]
                  tag)
                [:div {:style (when closed? {:display "none"})}
                 (om/build artifacts-node
                           {:artifacts children
                            :show-artifact-links? show-artifact-links?}
                           {:opts (assoc opts
                                    :ancestors-closed? (or (:ancestors-closed? opts) closed?))})]]))
           (sort-by first artifacts))])))))

(defn should-show-artifact-links?
  ;; Be extra careful about XSS in production environment
  [env admin?]
  (or (not= env "production")
      (not admin?)))

(defn build-artifacts-list [data owner]
  (reify
    om/IRender
    (render [_]
      (let [artifacts-data (:artifacts-data data)
            artifacts (:artifacts artifacts-data)
            has-artifacts? (:has-artifacts? data)]
        (html
         [:div.build-artifacts-container
          (if-not has-artifacts?
            (artifacts-ad)
            (if artifacts
              (map (fn artifact-node-builder [[node-index node-artifacts]]
                     (om/build artifacts-node {:artifacts (artifacts-tree (str "Container " node-index) node-artifacts)
                                               :show-artifact-links? (should-show-artifact-links? (env) (:admin (:user data)))}))
                   (->> artifacts
                        (group-by :node_index)
                        (sort-by first)))
              [:div.loading-spinner common/spinner]))])))))

(defn tests-ad [owner]
  [:div
   "With some of our inferred build commands we collect test metadata automatically, but that didn't happen for this project.  Collecting metadata allows us to list the specific failures, and in some cases makes parallel builds more efficient.  Here's how to get it:"
   [:ul
    [:li "For an inferred ruby test command, simply add the necessary "
     [:a {:href "/docs/test-metadata#automatic-test-metadata-collection"} "formatter gem"]]
    [:li "For another inferred test runner that you'd like us to add metadata support for, "
     [:a (common/contact-support-a-info owner)
      "let us know"] "."]
    [:li "For a custom test command, configure your test runner to write a JUnit XML report to a directory in $CIRCLE_TEST_REPORTS - see "
     [:a {:href "/docs/test-metadata#metadata-collection-in-custom-test-steps"} "the docs"] " for more information."]]])

(defn test-item [test owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:li
         (test-model/format-test-name test)
         (when-not (string/blank? (:message test))
           [:a {:role "button"
                :on-click #(raise! owner [:show-test-message-toggled {:test-index (:i test)}])}
            " more info "
            (if (:show-message test) [:i.fa.fa-caret-up] [:i.fa.fa-caret-down])])
         (when (:show-message test)
           [:pre (:message test)])]))))

(defn build-tests-list [data owner]
  (reify
    om/IRender
    (render [_]
      (let [tests-data (:tests-data data)
            tests (when (:tests tests-data)
                    (map-indexed #(assoc %2 :i %1) (:tests tests-data)))
            sources (reduce (fn [s test] (conj s (test-model/source test))) #{} tests)
            failed-tests (filter #(contains? #{"failure" "error"} (:result %)) tests)]
        (html
         [:div.build-tests-container
          (if-not tests
            [:div.loading-spinner common/spinner]
            (if (empty? tests)
              (tests-ad owner)
              [:div.build-tests-info
               [:div.build-tests-summary
                (str "Your build ran " (pluralize (count tests) "test") " in "
                     (string/join ", " (map test-model/pretty-source sources))
                     " with " (pluralize (count failed-tests) "failure") ".")]
               (when (seq failed-tests)
                 (for [[source tests-by-source] (group-by test-model/source failed-tests)]
                   [:div.build-tests-list-container
                    [:span.failure-source (str (test-model/pretty-source source) " failures:")]
                    [:ol.build-tests-list
                     (for [[file tests-by-file] (group-by :file tests-by-source)]
                       (list (when file [:div.filename (str file ":")])
                             (om/build-all test-item
                                           (vec (sort-by test-model/format-test-name tests-by-file)))))]]))]))])))))

(defn circle-yml-ad []
  [:div
   [:p "We didn't find a circle.yml for this build. You can specify deployment or override our inferred test steps from a circle.yml checked in to your repo's root directory."]
   [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "configuration"})} "in our docs"] "."]])

(defn build-config [{:keys [config-string]} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (if (seq config-string)
         [:div.build-config-string [:pre config-string]]
         (circle-yml-ad))))))

(defn build-parameters [{:keys [build-parameters]} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.build-parameters
        [:pre (for [[k v] build-parameters
                    :let [pname (name k) pval (pr-str v)]]
                (str pname "=" pval \newline))]]))))

(defn expected-duration
  [{:keys [start stop build]} owner opts]
  (reify

    om/IDisplayName
    (display-name [_] "Expected Duration")

    om/IDidMount
    (did-mount [_]
      (timer/set-updating! owner (not stop)))

    om/IDidUpdate
    (did-update [_ _ _]
      (timer/set-updating! owner (not stop)))

    om/IRender
    (render [_]
      (let [end-ms (if stop
                     (.getTime (js/Date. stop))
                     (datetime/server-now))
            formatter (get opts :formatter datetime/as-duration)
            duration-ms (- end-ms (.getTime (js/Date. start)))
            previous-build (:previous_successful_build build)
            past-ms (:build_time_millis previous-build)]
        (if (and past-ms
                 (= (:status build) "running")
                 (< duration-ms (* 1.5 past-ms)))
          (dom/span nil "/~" (formatter past-ms))
          (dom/span nil ""))))))

(defn default-tab
  "The default tab to show in the build page head, if they have't clicked a different tab."
  [build scopes]
  (cond
   ;; default to ssh-info for SSH builds
   (build-model/ssh-enabled-now? build) :ssh-info
   ;; default to the queue tab if the build is currently usage queued, and
   ;; the user is has the right permissions (and is logged in).
   (and (:read-settings scopes)
        (build-model/in-usage-queue? build))
   :usage-queue
   ;; Otherwise, just use the first one.
   :else :commits))

(defn build-sub-head [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            scopes (:scopes data)
            user (:user data)
            logged-in? (not (empty? user))
            admin? (:admin user)
            build (:build build-data)
            selected-tab (get build-data :selected-header-tab (default-tab build scopes))
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            config-data (:config-data build-data)
            build-params (:build_parameters build)]
        (html
         [:div.sub-head
          [:div.sub-head-top
           [:ul.nav.nav-tabs
            [:li {:class (when (= :commits selected-tab) "active")}
             [:a {:on-click #(raise! owner [:build-header-tab-clicked {:tab :commits}])}
              "Commit Log"]]

            [:li {:class (when (= :config selected-tab) "active")}
             [:a {:on-click #(raise! owner [:build-header-tab-clicked {:tab :config}])}
              "circle.yml"]]

            (when (seq build-params)
              [:li {:class (when (= :build-parameters selected-tab) "active")}
               [:a {:on-click #(raise! owner [:build-header-tab-clicked {:tab :build-parameters}])}
                "Build Parameters"]])

            (when (has-scope :read-settings data)
              [:li {:class (when (= :usage-queue selected-tab) "active")}
               [:a#queued_explanation
                {:on-click #(do (raise! owner [:build-header-tab-clicked {:tab :usage-queue}])
                                (raise! owner [:usage-queue-why-showed
                                               {:build-id build-id
                                                :username (:username @build)
                                                :reponame (:reponame @build)
                                                :build_num (:build_num @build)}]))}
                "Queue"
                (when (:usage_queued_at build)
                  [:span " ("
                   (om/build common/updating-duration {:start (:usage_queued_at build)
                                                       :stop (or (:start_time build) (:stop_time build))})
                   ")"])]])

            ;; XXX Temporarily remove the ssh info for OSX builds
            (when (and (has-scope :write-settings data)
                       (not (get-in project [:feature_flags :osx])))
              [:li {:class (when (= :ssh-info selected-tab) "active")}
               [:a {:on-click #(raise! owner [:build-header-tab-clicked {:tab :ssh-info}])}
                "Debug via SSH"]])

            ;; tests don't get saved until the end of the build (TODO: stream the tests!)
            (when (build-model/finished? build)
              [:li {:class (when (= :tests selected-tab) "active")}
               [:a {:on-click #(do
                                 (raise! owner [:build-header-tab-clicked {:tab :tests}])
                                 (raise! owner [:tests-showed]))}
                (if (= "success" (:status build))
                  "Test Results "
                  "Test Failures ")
                (when-let [fail-count (some->> build-data
                                               :tests-data
                                               :tests
                                               (filter #(contains? #{"failure" "error"} (:result %)))
                                               count)]
                  (when (not= 0 fail-count)
                    [:span {:class "fail-count"} fail-count]))]])

            (when (and admin? (build-model/finished? build))
              [:li {:class (when (= :build-time-viz selected-tab) "active")}
               [:a {:on-click #(raise! owner [:build-header-tab-clicked {:tab :build-time-viz}])}
                "Build Timing"]])

            ;; artifacts don't get uploaded until the end of the build (TODO: stream artifacts!)
            (when (and logged-in? (build-model/finished? build))
              [:li {:class (when (= :artifacts selected-tab) "active")}
               [:a {:on-click #(do (raise! owner [:build-header-tab-clicked {:tab :artifacts}])
                                   (raise! owner [:artifacts-showed]))}
                "Artifacts"]])]]

          [:div.sub-head-content
           (case selected-tab
             :commits (om/build build-commits build-data)

             :tests (om/build build-tests-list build-data)

             :build-time-viz (om/build build-time-visualization build)

             :artifacts (om/build build-artifacts-list
                                  {:artifacts-data (get build-data :artifacts-data) :user user
                                   :has-artifacts? (:has_artifacts build)})

             :config (om/build build-config {:config-string (get-in build [:circle_yml :string])})

             :build-parameters (om/build build-parameters {:build-parameters build-params})

             :usage-queue (om/build build-queue {:build build
                                                 :builds (:builds usage-queue-data)
                                                 :plan plan})
             :ssh-info (om/build build-ssh {:build build :user user}))]])))))

(defn build-head [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            build (:build build-data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            user (:user data)
            logged-in? (not (empty? user))
            config-data (:config-data build-data)
            build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
         [:div.build-head-wrapper
          [:div.build-head
           [:div.build-info
            [:table
             [:tbody
              [:tr
               [:th "Author"]
               [:td (if-not (:author_email build)
                      [:span (build-model/author build)]
                      [:a {:href (str "mailto:" (:author_email build))}
                       (build-model/author build)])]
               [:th "Started"]
               [:td (when (:start_time build)
                      {:title (datetime/full-datetime (:start_time build))})
                (when (:start_time build)
                  (list (om/build common/updating-duration
                                  {:start (:start_time build)}
                                  {:opts {:formatter datetime/time-ago}}) " ago"))]]
              [:tr
               [:th "Trigger"]
               [:td (build-model/why-in-words build)]

               [:th "Duration"]
               [:td (if (build-model/running? build)
                      (om/build common/updating-duration {:start (:start_time build)
                                                          :stop (:stop_time build)})
                      (build-model/duration build))
                    (om/build expected-duration {:start (:start_time build)
                                                :stop (:stop_time build)
                                                :build build})]]
              [:tr
               [:th "Previous"]
               (if-not (:previous build)
                 [:td "none"]
                 [:td
                  [:a {:href (routes/v1-build-path (vcs-url/org-name vcs-url) (vcs-url/repo-name vcs-url) (:build_num (:previous build)))}
                   (:build_num (:previous build))]])
               [:th "Status"]
               [:td
                [:span.build-status {:class (:status build)}
                 (build-model/status-words build)]
                (when-let [canceler (and (= (:status build) "canceled")
                                         (:canceler build))]
                  [:span.build-canceler
                   (list "by "
                         [:a {:href (str (github-endpoint) "/" (:login canceler))}
                          (if (not-empty (:name canceler))
                            (:name canceler)
                            (:login canceler))])])]]
              [:tr
               (when (:usage_queued_at build)
                 (list [:th "Queued"]
                       [:td (if (< 0 (build-model/run-queued-time build))
                              [:span
                               (om/build common/updating-duration {:start (:usage_queued_at build)
                                                                   :stop (or (:queued_at build) (:stop_time build))})
                               " waiting + "
                               (om/build common/updating-duration {:start (:queued_at build)
                                                                   :stop (or (:start_time build) (:stop_time build))})
                               " in queue"]

                              [:span
                               (om/build common/updating-duration {:start (:usage_queued_at build)
                                                                   :stop (or (:queued_at build) (:stop_time build))})
                               " waiting for builds to finish"])]))
               (when (build-model/author-isnt-committer build)
                 (list [:th "Committer"]
                       [:td
                        (if-not (:committer_email build)
                          [:span (build-model/committer build)]
                          [:a {:href (str "mailto:" (:committer_email build))}
                           (build-model/committer build)])]))]
              [:tr
               [:th "Parallelism"]
               [:td
                (if (has-scope :write-settings data)
                  [:a.parallelsim-link-head {:title (str "This build used " (:parallel build) " containers. Click here to change parallelism for future builds.")
                       :href (build-model/path-for-parallelism build)}
                   (str (:parallel build) "x")]
                  [:span (:parallel build) "x"])]
               (when-let [urls (seq (:pull_request_urls build))]
                 ;; It's possible for a build to be part of multiple PRs, but it's rare
                 (list [:th (str "PR" (when (< 1 (count urls)) "s"))]
                       [:td
                        (interpose
                         ", "
                         (map (fn [url] [:a {:href url} "#"
                                         (let [n (re-find #"/\d+$" url)]
                                           (if n (subs n 1) "?"))])
                              urls))]))]]]
            [:div.build-actions
             (when (has-scope :write-settings data)
               [:div.actions
                (forms/managed-button
                 [:button.retry_build
                  {:data-loading-text "Rebuilding",
                   :title "Retry the same tests",
                   :on-click #(raise! owner [:retry-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num
                                                                   :no-cache? false}])}
                  "Rebuild"])

                (forms/managed-button
                 [:button.without_cache_retry
                  {:data-loading-text "Rebuilding",
                   :title "Retry without cache",
                   :on-click #(raise! owner [:retry-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num
                                                                   :no-cache? true}])}
                  "without cache"])

                ;; XXX Temporarily remove the ssh button for OSX builds
                (when (not (get-in project [:feature_flags :osx]))
                  (forms/managed-button
                   [:button.ssh_build
                    {:data-loading-text "Rebuilding",
                     :title "Retry with SSH in VM",
                     :on-click #(raise! owner [:ssh-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num}])}
                    "with ssh"]))])
             [:div.actions
              ;; TODO: Handle when intercom isn't enabled
              (when (and logged-in? (intercom-enabled?)) ;; no intercom for logged-out users
                [:button.report_build
                 {:title "Report error with build",
                  :on-click #(raise! owner [:report-build-clicked {:build-url (:build_url @build)}])}
                 "Report"])
              (when (and (build-model/can-cancel? build) (has-scope :write-settings data))
                (forms/managed-button
                  [:button.cancel_build
                   {:data-loading-text "Canceling",
                    :title "Cancel this build",
                    :on-click #(raise! owner [:cancel-build-clicked {:build-id build-id
                                                                     :vcs-url vcs-url
                                                                     :build-num build-num}])}
                   "Cancel"]))]]
[:div.no-user-actions]]

           (om/build build-sub-head data)]])))))
