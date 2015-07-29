(ns frontend.components.header
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [raise!]]
            [frontend.config :as config]
            [frontend.components.common :as common]
            [frontend.components.crumbs :as crumbs]
            [frontend.components.forms :as forms]
            [frontend.components.instrumentation :as instrumentation]
            [frontend.components.statuspage :as statuspage]
            [frontend.models.project :as project-model]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :refer [auth-url]]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn show-follow-project-button? [app]
  (when-let [project (get-in app state/project-path)]
    (and (not (:followed project))
         (= (vcs-url/org-name (:vcs_url project))
            (get-in app [:navigation-data :org]))
         (= (vcs-url/repo-name (:vcs_url project))
            (get-in app [:navigation-data :repo])))))

(defn show-settings-link? [app]
  (:read-settings (get-in app state/page-scopes-path)))

(defn settings-link [app owner]
  (when (show-settings-link? app)
    (let [navigation-data (:navigation-data app)]
      (cond (:repo navigation-data) [:a.settings.project-settings
                                     {:href (routes/v1-project-settings navigation-data) }
                                     (common/ico :settings-light) "Project Settings"]
            (:org navigation-data) [:a.settings.org-settings
                                    {:href (routes/v1-org-settings navigation-data)}
                                    (common/ico :settings-light) "Organization Settings"]
            :else nil))))

(defn head-user [app owner]
  (reify
    om/IDisplayName (display-name [_] "User Header")
    om/IRender
    (render [_]
      (let [crumbs-data (get-in app state/crumbs-path)
            project (get-in app state/project-path)
            project-id (project-model/id project)
            vcs-url (:vcs_url project)]
        (html
          [:div.head-user
           [:div.breadcrumbs
            (when (seq crumbs-data)
              [:a {:title "home", :href "/"} [:i.fa.fa-home] " "])
            (crumbs/crumbs crumbs-data)]
           (when (show-follow-project-button? app)
             (forms/managed-button
               [:button#follow-project-button
                {:on-click #(raise! owner [:followed-project {:vcs-url vcs-url :project-id project-id}])
                 :data-spinner true}
                "follow the " (vcs-url/repo-name vcs-url) " project"]))
           (settings-link app owner)])))))

(defn head-admin [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Header")
    om/IRender
    (render [_]
      (let [open? (get-in app state/show-admin-panel-path)
            expanded? (get-in app state/show-instrumentation-line-items-path)
            inspector? (get-in app state/show-inspector-path)
            user-session-settings (get-in app [:render-context :user_session_settings])
            env (config/env)]
        (html
         [:div.head-admin {:class (concat (when open? ["open"])
                                          (when expanded? ["expanded"]))}
          [:div.admin-tools
           [:div.environment {:class (str "env-" env)
                              :role "button"
                              :on-click #(raise! owner [:show-admin-panel-toggled])}
            env]

           [:div.options
            [:a {:href "/admin/switch"} "switch "]
            [:a {:href "/admin/build-state"} "build state "]
            [:a {:href "/admin/recent-builds"} "builds "]
            [:a {:href "/admin/deployments"} "deploys "]
            (let [use-local-assets (get user-session-settings :use_local_assets)]
              [:a {:on-click #(raise! owner [:set-user-session-setting {:setting :use-local-assets
                                                                        :value (not use-local-assets)}])}
               "local assets " (if use-local-assets "off " "on ")])
            (let [current-build-id (get user-session-settings :om_build_id "dev")]
              (for [build-id (remove (partial = current-build-id) ["dev" "whitespace" "production"])]
                [:a.menu-item
                 {:on-click #(raise! owner [:set-user-session-setting {:setting :om-build-id
                                                                       :value build-id}])}
                 [:span (str "om " build-id " ")]]))
            [:a {:on-click #(raise! owner [:show-inspector-toggled])}
             (if inspector? "inspector off " "inspector on ")]
            [:a {:on-click #(raise! owner [:clear-instrumentation-data-clicked])} "clear stats"]]
           (om/build instrumentation/summary (:instrumentation app))]
          (when (and open? expanded?)
            (om/build instrumentation/line-items (:instrumentation app)))])))))

(defn maybe-active [current goal]
  {:class (when (= current goal)
            "active")})

(defn outer-subheader [nav-maps nav-point]
  (map
   #(when (-> %
              keys
              set
              (contains? nav-point))
      [:div.navbar.navbar-default.navbar-static-top.subnav
       [:div.container-fluid
        [:ul.nav.navbar-nav
         (for [[point {:keys [path title]}] %]
           [:li (maybe-active nav-point point)
            [:a {:href path} title]])]]])
   nav-maps))

(defn outer-header [app owner]
  (reify
    om/IDisplayName (display-name [_] "Outer Header")
    om/IRender
    (render [_]
      (let [flash (get-in app state/flash-path)
            logged-in? (get-in app state/user-path)
            nav-point (:navigation-point app)]
        (html
         [:div
          [:div
           (when flash
             [:div#flash {:dangerouslySetInnerHTML {:__html flash}}])]
          ;; TODO: Temporary hack until new header ships
          [:div.navbar.navbar-default.navbar-static-top {:class (case nav-point
                                                                  :language-landing
                                                                  (get-in app [:navigation-data :language])
                                                                  :integrations
                                                                  (name (get-in app [:navigation-data :integration]))
                                                                  nil)}
           [:div.container-fluid
            [:div.navbar-header
             [:a#logo.navbar-brand
              {:href "/"}
              (common/circle-logo {:width nil
                                   :height 25})]]
            [:ul.nav.navbar-nav
             (when (config/show-marketing-pages?)
               (list
                [:li.dropdown {:class (when (contains? #{:features
                                                         :mobile
                                                         :ios
                                                         :android
                                                         :integrations
                                                         :enterprise}
                                                       nav-point)
                                        "active")}
                 [:a {:href "/features"}
                  "Product "
                  [:i.fa.fa-caret-down]]
                 [:ul.dropdown-menu
                  [:li {:role "presentation"}
                   [:a {:role "menuitem"
                        :tabIndex "-1"
                        :href "/features"}
                    "Features"]]
                  [:li {:role "presentation"}
                   [:a {:role "menuitem"
                        :tabIndex "-1"
                        :href "/mobile"}
                    "Mobile"]]
                  [:li {:role "presentation"}
                   [:a {:role "menuitem"
                        :tabIndex "-1"
                        :href "/integrations/docker"}
                    "Docker"]]
                  [:li {:role "presentation"}
                   [:a {:role "menuitem"
                        :tabIndex "-1"
                        :href "/enterprise"}
                    "Enterprise"]]]]
                [:li (maybe-active nav-point :pricing)
                 [:a {:href "/pricing"} "Pricing"]]))
             [:li (maybe-active nav-point :documentation)
              [:a {:href "/docs"} "Documentation"]]
             (when (config/show-marketing-pages?)
               (list
                [:li {:class (when (contains? #{:about
                                                :contact
                                                :team
                                                :jobs
                                                :press}
                                              nav-point)
                               "active")}
                 [:a {:href "/about"} "About Us"]]
                [:li [:a {:href "http://blog.circleci.com"} "Blog"]]))]

            (if logged-in?
              [:ul.nav.navbar-nav.navbar-right
               [:li [:a {:href "/"} "Back to app"]]]
              [:ul.nav.navbar-nav.navbar-right
               [:li
                [:a.login.login-link {:href (auth-url)
                                      :on-click #(raise! owner [:track-external-link-clicked {:path (auth-url) :event "login_click" :properties {:source "header log-in" :url js/window.location.pathname}}])
                                      :title "Log In with Github"}
                 "Log In"]]
               [:li
                [:button.login-link.btn.btn-success.navbar-btn {:href (auth-url)
                                                     :on-click #(raise! owner [:track-external-link-clicked {:path (auth-url) :event "signup_click" :properties {:source "header sign-up" :url js/window.location.pathname}}])
                                                     :title "Sign up with Github"}
                 "Sign Up"]]])]]
          (outer-subheader
           [{:mobile {:path "/mobile"
                       :title "Mobile"}
              :ios {:path "/mobile/ios"
                    :title "iOS"}
              :android {:path "/mobile/android"
                        :title "Android"}}
            {:about {:path "/about"
                     :title "Overview"}
             :team {:path "/about/team"
                    :title "Team"}
             :contact {:path "/contact"
                       :title "Contact Us"}
             :jobs {:path "/jobs"
                    :title "Jobs"}
             :press {:path "/press"
                     :title "Press"}}
            {:enterprise {:path "/enterprise"
                          :title "Overview"}
             :azure {:path "/enterprise/azure"
                     :title "Azure"}
             :aws {:path "/enterprise/aws"
                   :title "AWS"}}]
           nav-point)])))))

(defn inner-header [app owner]
  (reify
    om/IDisplayName (display-name [_] "Inner Header")
    om/IRender
    (render [_]
      (let [admin? (if (config/enterprise?)
                     (get-in app [:current-user :dev-admin])
                     (get-in app [:current-user :admin]))
            logged-out? (not (get-in app state/user-path))]
        (html
         [:header.main-head (when logged-out? {:class "guest"})
          (when admin?
            (om/build head-admin app))
          (when (config/statuspage-header-enabled?)
            (om/build statuspage/statuspage app))
          (when logged-out?
            (om/build outer-header app))
          (when (seq (get-in app state/crumbs-path))
            (om/build head-user app))])))))


(defn header [app owner]
  (reify
    om/IDisplayName (display-name [_] "Header")
    om/IRender
    (render [_]
      (let [inner? (get-in app state/inner?-path)
            logged-in? (get-in app state/user-path)
            _ (utils/mlog "header render inner? " inner? " logged-in? " logged-in?)]
        (html
          (if inner?
            (om/build inner-header app)
            (om/build outer-header app)))))))
