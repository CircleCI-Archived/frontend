(ns frontend.components.invites
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [frontend.async :refer [raise!]]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn invitees
  "Filters users to invite and returns only fields needed by invitation API"
  [users]
  (->> users
       (filter (fn [u] (and (:email u)
                            (:checked u))))
       (map (fn [u] (select-keys u [:email :login :id])))
       vec))

(defn invite-tile [user owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [avatar_url email login index]} user]
        (html
         [:li
          [:div.invite-gravatar
           [:img {:src (gh-utils/make-avatar-url user)}]]
          [:div.invite-profile
           login
           [:input {:on-change #(utils/edit-input owner (conj (state/invite-github-user-path index) :email) %)
                    :required true
                    :type "email"
                    :value email
                    :id (str login "-email")}]
           [:label {:for (str login "-email")}
            [:i.fa.fa-exclamation-circle
             {:title "Entering an Email address is required for sending invites to this user"}]
            " Email not available"]]
          [:label.invite-select {:id (str login "-label")
                                 :for (str login "-checkbox")}
           [:input {:type "checkbox"
                    :id (str login "-checkbox")
                    :checked (boolean (:checked user))
                    :on-change #(utils/toggle-input owner (conj (state/invite-github-user-path index) :checked) %)}]
           [:div.checked \uf046]
           [:div.unchecked \uf096]]])))))

(defn invites [users owner opts]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         [:section
          [:a {:role "button"
               :on-click #(raise! owner [:invite-selected-all])}
           "all"]
          " / "
          [:a {:role "button"
               :on-click #(raise! owner [:invite-selected-none])}
           "none"]
          [:ul
           (om/build-all invite-tile users {:key :login})]]
         [:footer
          (forms/managed-button
           [:button (let [users-to-invite (invitees users)]
                      {:data-success-text "Sent"
                       :on-click #(raise! owner [:invited-github-users
                                                 (merge {:invitees users-to-invite}
                                                        (if (:project-name opts)
                                                          {:project-name (:project-name opts)}
                                                          {:org-name (:org-name opts)}))])})

            "Send Invites "
            [:i.fa.fa-envelope-o]])]]))))

(defn build-invites [invite-data owner opts]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [project-name (:project-name opts)]
        (raise! owner [:load-first-green-build-github-users {:project-name project-name}])))
    om/IRender
    (render [_]
      (let [project-name (:project-name opts)
            users (remove :following (:github-users invite-data))
            dismiss-form (:dismiss-invite-form invite-data)]
        (html
         [:div.first-green.invite-form {:class (when (or (empty? users) dismiss-form)
                                                 "animation-fadeout-collapse")}
          [:button {:on-click #(raise! owner [:dismiss-invite-form])}
           [:span "Dismiss "] [:i.fa.fa-times-circle]]
          [:header
           [:div.head-left
            (common/icon {:type :status :name :pass})]
           [:div.head-right
            [:h2 "Congratulations!"]
            [:p "You just got your first green build! Invite some of your collaborators below and never test alone!"]]]
          (om/build invites users {:opts opts})])))))

(defn side-item [org owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:li.side-item
         [:a {:href (str "/invite-teammates/organization/" (:login org))}
          [:img {:src (gh-utils/make-avatar-url org :size 25)
                 :width 25 :height 25}]
          [:div.orgname (:login org)]]]))))

(defn teammates-invites [data owner opts]
  (reify
    om/IRender
    (render [_]
      (let [invite-data (:invite-data data)]
        (html
          [:div#invite-teammates
           ; org bar on the left, borrowed from add projects
           [:ul.side-list
            (om/build-all side-item (get-in data state/user-organizations-path))]
           ; invites box on the right
           (if (:org invite-data)
             [:div.first-green.invite-form
              [:h3 "Invite your " (:org invite-data) " teammates"]
              (om/build invites
                        (remove :circle_member (:github-users invite-data))
                        {:opts {:org-name (:org invite-data)}}) ]
             [:div.org-invites
               [:h3 "Invite your teammates"]
               [:p "Select one of your organizations on the left to select teammates to invite.  Or send them this link:"]
               [:p [:input {:value "https://circleci.com/?join=dont-test-alone", :type "text"}]]
               [:p "We use GitHub permissions for every user, so if your teammates have access to your project on GitHub, they will automatically have access to the same project on Circle."]]
             )])))))
