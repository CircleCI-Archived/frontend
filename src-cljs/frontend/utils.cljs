(ns frontend.utils
  (:refer-clojure :exclude [uuid])
  (:require [ajax.core :as ajax]
            [cljs-time.core :as time]
            [cljs.core.async :as async :refer [<! >! alts! chan close! sliding-buffer]]
            [clojure.string :as string]
            [frontend.async :refer [put! raise!]]
            [frontend.config :as config]
            [frontend.state :as state]
            goog.async.AnimationDelay
            [goog.crypt :as crypt]
            [goog.crypt.Md5 :as md5]
            [goog.dom :as dom]
            [goog.events :as ge]
            [goog.net.EventType :as gevt]
            [goog.object :as gobject]
            [goog.string :as gstring]
            [goog.style :as style]
            goog.Uri
            [om.core :as om :include-macros true]
            [sablono.core :as html :include-macros true])
  (:require-macros [frontend.utils :refer [inspect timing defrender html]])
  (:import [goog.format EmailAddress]))

(defn csrf-token []
  (aget js/window "CSRFToken"))

(defn oauth-csrf-token []
  (or (aget js/window "OAuthCSRFToken")
      (aget js/window "GitHubCSRFToken")))

(def parsed-uri
  (goog.Uri. (-> (.-location js/window) (.-href))))

(defn parse-uri-bool
  "Parses a boolean from a url into true, false, or nil"
  [string]
  (case string
    "true" true
    "false" false
    nil))

(defn clj-key->js-key [kw]
  "Converts a clj key (:foo-bar) to a js key (foo_bar)."
  (-> kw
      name
      (string/replace #"-" "_")))

(defn clj-keys-with-dashes->clj-keys-with-underscores [clj-map]
  "Recursively convert a map to replace all the dashes with underscores
  in the keys only. This is mean for converting to a clojure object which
  looks like a javscript object in form, so we can merge it with JS objects."
  (->> (for [[kw value] clj-map]
         [(-> kw
              clj-key->js-key
              keyword)
          (if (map? value)
            (clj-keys-with-dashes->clj-keys-with-underscores value)
            value)])
       (into {})))

(defn clj-keys-with-dashes->js-keys-with-underscores [clj-map]
  "Same as clj->js but also converts dashes to underscores in key only.
  This leaves the key as a string so its only to be used immediately prior
  to sending data to third party services."
  (->> (for [[kw value] clj-map]
         [(clj-key->js-key kw)
          (if (map? value)
            (clj-keys-with-dashes->js-keys-with-underscores value)
            value)])
       (into {})
       (clj->js)))

(defn uri-to-relative
  "Returns relative uri e.g. \"/a/b/c\" for \"http://yahoo.com/a/b/c\""
  [uri]
  (-> (goog.Uri. uri)
      (.getPath)))

(def initial-query-map
  {:log-channels? (parse-uri-bool (.getParameterValue parsed-uri "log-channels"))
   :logging-enabled? (parse-uri-bool (.getParameterValue parsed-uri "logging-enabled"))
   :restore-state? (parse-uri-bool (.getParameterValue parsed-uri "restore-state"))
   :rethrow-errors? (parse-uri-bool (.getParameterValue parsed-uri "rethrow-errors"))
   :inspector? (parse-uri-bool (.getParameterValue parsed-uri "inspector"))
   :render-colors? (parse-uri-bool (.getParameterValue parsed-uri "render-colors"))
   :invited-by (.getParameterValue parsed-uri "invited-by")})

(defn logging-enabled? []
  (:logging-enabled? initial-query-map
                     (config/logging-enabled?)))

(defn mlog [& messages]
  (when (logging-enabled?)
    (.apply (.-log js/console) js/console (clj->js messages))))

(defn mwarn [& messages]
  (when (logging-enabled?)
    (.apply (.-warn js/console) js/console (clj->js messages))))

(defn merror [& messages]
  (when (logging-enabled?)
    (.apply (.-error js/console) js/console (clj->js messages))))

(defn uuid
  "returns a type 4 random UUID: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
  []
  (let [r (repeatedly 30 (fn [] (.toString (rand-int 16) 16)))]
    (apply str (concat (take 8 r) ["-"]
                       (take 4 (drop 8 r)) ["-4"]
                       (take 3 (drop 12 r)) ["-"]
                       [(.toString  (bit-or 0x8 (bit-and 0x3 (rand-int 15))) 16)]
                       (take 3 (drop 15 r)) ["-"]
                       (take 12 (drop 18 r))))))

(defn md5 [content]
  (let [container (goog.crypt.Md5.)]
    (.update container content)
    (crypt/byteArrayToHex (.digest container))))

(defn notify-error [ch message]
  (put! ch [:error-triggered message]))

(defn trim-middle [s length]
  (let [str-len (count s)]
    (if (<= str-len (+ length 3))
      s
      (let [over (+ (- str-len length) 3)
            slice-pos (.ceil js/Math (/ (- length 3) 3))]
        (str (subs s 0 slice-pos)
             "..."
             (subs s (+ slice-pos over)))))))

(defn third [coll]
  (nth coll 2 nil))

(defn js->clj-kw
  "Same as js->clj, but keywordizes-keys by default"
  [ds]
  (js->clj ds :keywordize-keys true))

(defn cdn-path
  "Returns path of asset in CDN"
  [path]
  (str (config/assets-root) (if (= \/ (first path))
                              path
                              (str "/" path))))

(defn display-tag [tag]
  (-> tag name js/decodeURIComponent (str " (tag)")))

(defn display-branch [branch]
  (-> branch name js/decodeURIComponent))

(defn encode-branch [branch]
  (-> branch name js/encodeURIComponent))

;; Stores unique keys to uuids for the functions
(def debounce-state (atom {}))

(defn debounce
  "Takes a unique key and a function, will only execute the last function
   in a sliding 20ms interval (slightly longer than 16ms, time for rAF, seems to work best)"
  [unique-key f & {:keys [timeout]
                   :or {timeout 100}}]
  (js/clearTimeout (get @debounce-state unique-key))
  (let [timeout-id (js/setTimeout f timeout)]
    (swap! debounce-state assoc unique-key timeout-id)))

(defn edit-input
  "Meant to be used in a react event handler, usually for the :on-change event on input.
  Path is the vector of keys you would pass to assoc-in to change the value in state,
  event is the Synthetic React event. Pulls the value out of the event.
  Optionally takes :value as a keyword arg to override the event's value"
  [owner path event & {:keys [value]
                       :or {value (.. event -target -value)}}]
  (raise! owner [:edited-input {:path path :value value}]))

(defn toggle-input
  "Meant to be used in a react event handler, usually for the :on-change event on input.
  Path is the vector of keys you would pass to update-in to toggle the value in state,
  event is the Synthetic React event."
  [owner path event]
  (raise! owner [:toggled-input {:path path}]))

;; TODO: get rid of bootstrap popovers
(defn popover
  "Sets up a popover given selector and options. Once this is called, the popover
   should work as expected"
  [selector options]
  (mwarn "Please remove the popover on" selector)
  (let [jq (aget js/window "$")
        $node (jq selector)
        $popover (aget $node "popover")]
    (.call $popover $node (clj->js options))))

;; TODO: get rid of bootstrap tooltips
(defn tooltip
  "Sets up a tooltip given selector and options. Once this is called, the tooltip
   should work as expected"
  [selector & [options]]
  (mwarn "Please remove the tooltip on" selector)
  (let [jq (aget js/window "$")
        $node (jq selector)
        $tooltip (aget $node "tooltip")]
    (if options
      (.call $tooltip $node (clj->js options))
      (.call $tooltip $node))))

;; TODO: get rid of bootstrap typeahead
(defn typeahead
  "Sets up typahead given selector and options. Once this is called, typeahead
   should work as expected"
  [selector & [options]]
  (mwarn "Please remove typeahead on" selector)
  (let [jq (aget js/window "$")
        $node (jq selector)
        $typeahead (aget $node "typeahead")]
    (.call $typeahead $node (clj->js options))))

(defn rAF
  "Calls passed in function inside a requestAnimationFrame, falls back to timeouts for
   browers without requestAnimationFrame"
  [f]
  (.start (goog.async.AnimationDelay. f)))

(defn valid-email? [str]
  (.isValidAddrSpec EmailAddress str))

(defn deep-merge* [& maps]
  (let [f (fn [old new]
            (if (and (map? old) (map? new))
              (merge-with deep-merge* old new)
              new))]
    (if (every? map? maps)
      (apply merge-with f maps)
      (last maps))))

(defn deep-merge
  "Merge nested maps. At each level maps are merged left to right. When all
  maps have a common key whose value is also a map, those maps are merged
  recursively. If any of the values are not a map then the value from the
  right-most map is chosen.

  E.g.:
  user=> (deep-merge {:a {:b 1}} {:a {:c 3}})
  {:a {:c 3, :b 1}}

  user=> (deep-merge {:a {:b 1}} {:a {:b 2}})
  {:a {:b 2}}

  user=> (deep-merge {:a {:b 1}} {:a {:b {:c 4}}})
  {:a {:b {:c 4}}}

  user=> (deep-merge {:a {:b {:c 1}}} {:a {:b {:e 2 :c 15} :f 3}})
  {:a {:f 3, :b {:e 2, :c 15}}}

  Each of the arguments to this fn must be maps:

  user=> (deep-merge {:a 1} [1 2])
  AssertionError Assert failed: (and (map? m) (every? map? ms))

  Like merge, a key that maps to nil will override the same key in an earlier
  map that maps to a non-nil value:

  user=> (deep-merge {:a {:b {:c 1}, :d {:e 2}}}
                     {:a {:b nil, :d {:f 3}}})
  {:a {:b nil, :d {:f 3, :e 2}}}"
  [& maps]
  (let [maps (filter identity maps)]
    (assert (every? map? maps))
    (apply merge-with deep-merge* maps)))


(defn set-page-title! [& [title]]
  (set! (.-title js/document) (if title
                                (str title  " - CircleCI")
                                "CircleCI")))

(defn set-page-description!
  [description]
  (let [meta-el (.querySelector js/document "meta[name=description]")]
    (.setAttribute meta-el "content" (str description))))

(defn set-canonical!
  "Upserts a canonical URL if canonical-page is not nil, otherwise deletes the canonical rel."
  [canonical-page]
  (if-let [link-el (.querySelector js/document "link[rel=\"canonical\"]")]
    (if (some? canonical-page)
      (.setAttribute link-el "href" canonical-page)
      (dom/removeNode link-el))
    (when (some? canonical-page)
      (let [new-link-el (dom/createElement "link")]
        (.setAttribute new-link-el "rel" "canonical")
        (.setAttribute new-link-el "href" canonical-page)
        (dom/appendChild (.-head js/document) new-link-el)))))

(defn node-height [node]
  (if node
    (+ (js/parseFloat (style/getComputedStyle node "marginTop"))
       (.-offsetHeight node)
       (js/parseFloat (style/getComputedStyle node "marginBottom")))
    0))

(defn scroll-to-node-bottom [node]
  (when node
    (.scrollIntoView node false)))

(defn scroll-to-build-action!
  [action-node]
  ;; FIXME: this should not be looking up the scrolling element by class name
  (let [scrolling-element (.querySelector js/document ".main-body")
        node-top (style/getPageOffsetTop action-node)
        container-list-height (node-height (dom/getElementByClass "container-list"))
        current-top (style/getPageOffsetTop scrolling-element)]
    (set! (.-scrollTop scrolling-element) (- node-top current-top container-list-height))))

(defn scroll-to-node!
  [node]
  ;; FIXME: this should not be looking up the scrolling element by class name
  (let [scrolling-element (.querySelector js/document ".main-body")
        node-top (style/getPageOffsetTop node)
        current-top (style/getPageOffsetTop scrolling-element)]
    (set! (.-scrollTop scrolling-element) (- node-top current-top))))

(defn scroll-to-id!
  "Scrolls to the element with given id, if node exists"
  [id]
  (when-let [node (dom/getElement id)]
    (scroll-to-node! node)))

(defn scroll-to-selector!
  "Scrolls to the first element matching the selector, if node exists"
  [selector]
  (when-let [node (.querySelector js/document selector)]
    (scroll-to-node! node)))

(defn scroll!
  "Scrolls to fragment if the url had one, or scrolls to the top of the page"
  [args]
  (if (:_fragment args)
    ;; give the page time to render
    (rAF #(scroll-to-id! (:_fragment args)))
    (rAF #(set! (.-scrollTop (.-body js/document)) 0))))

(defn react-id [x]
  (let [id (aget x "_rootNodeID")]
    (assert id)
    id))

(defn text [elem]
  (or (.-textContent elem) (.-innerText elem)))

(defn set-html!
  "Set the innerHTML of `elem` to `html`"
  [elem html]
  (set! (.-innerHTML elem) html)
  elem)

(defn sel1
  ([selector] (sel1 js/document selector))
  ([parent selector]
     (.querySelector parent selector)))

(defn sel
  ([selector] (sel js/document selector))
  ([parent selector]
   (.querySelectorAll parent selector)))

(defn node-list->seqable [node-list]
  (js/Array.prototype.slice.call node-list))

(defn equalize-size
  "Given a node, will find all elements under node that satisfy selector and change
   the size of every element so that it is the same size as the largest element."
  [node class-name]
  (let [items (node-list->seqable (dom/getElementsByClass class-name node))
        sizes (map style/getSize items)
        max-width (apply max (map #(.-width %) sizes))
        max-height (apply max (map #(.-height %) sizes))]
    (doseq [item items]
      (style/setSize item max-width max-height))))

;; For objects where externs are unavailable, directly access property.
;; This is used as a marker to prevent future "refactoring".
(def unexterned-prop aget)

(defn split-map-values-at
  "This is similar to `split-at`, but for maps.

M is a map of arrays : {k -> [xs], j -> [ys] , ...}.

Return [TOP-MAP BOTTOM-MAP]

M is split into top and bottom maps, fitting a max of TOP-MAX keys/values (i.e. `(take top-max (concat xs ys ...))` into the
top.  The remaining keys/values goes into the bottom."
  [m top-max]
  (loop [top-map (sorted-map)
         bottom-map (sorted-map)
         ;; `nil` is a valid key here BE CAREFUL
         ks (keys m)
         remaining top-max]
    (if (seq ks)
      (let [k (first ks)
            [top bottom] (split-at remaining (m k))]
        (recur (if (seq top)
                 (assoc top-map k top)
                 top-map)
               (if (seq bottom)
                 (assoc bottom-map k bottom)
                 bottom-map)
               (rest ks)
               (max (- remaining (count top))
                    0)))
      [top-map bottom-map])))

(defn current-user
  []
  (when-let [user (aget js/window "renderContext" "current_user")]
    (js->clj user :keywordize-keys true)))

(defn prettify-vcs_type
  "Takes a keyword vcs_type and converts it to a pretty string (e.g. :github becomes \"GitHub\")"
  [vcs_type]
  (case (keyword vcs_type)
    (:github "github") "GitHub"
    (:bitbucket "bitbucket") "Bitbucket"))

(defn keywordize-pretty-vcs_type
  "Takes a pretty vcs_type and converts it to a keyword. (e.g. \"GitHub\" becomes :github)"
  [vcs_type-pretty]
  (-> vcs_type-pretty
      clojure.string/lower-case
      keyword))


(defn- has-prop?
  "True if the React element has a value specified for the prop name."
  [element prop-name]
  (gobject/containsKey (.-props element) prop-name))

(defn component*
  "Gives a React element a data-component value of name, unless the element
  already has a data-component value. (Use the macro `component` instead; it
  validates that the name is a Var at compile time.)"
  [name element]
  (cond-> element
    (not (has-prop? element "data-component")) (js/React.cloneElement #js {:data-component name})))

(defn element*
  "Gives a React element a data-element value of element-name \"namespaced\" to
  component-name. (Use the macro `element` instead; it uses the component-name
  of the nearest `component` macro form."
  [element-name component-name element]
  (let [full-name (str component-name "/" element-name)]
    (assert (not (has-prop? element "data-component"))
            (str "Tried to assign data-element "
                 full-name
                 " to a React element which already has data-component."))
    (assert (not (has-prop? element "data-element"))
            (str "Tried to assign data-element "
                 full-name
                 " to a React element which already has data-element."))
    (js/React.cloneElement element #js {:data-element full-name})))

(defn disable-natural-form-submission
  "Disable natural form submission. This keeps us from having to
  .preventDefault every submit button on every form.

  To let a button actually submit a form naturally, handle its click
  event and call .stopPropagation on the event. That will stop the
  event from bubbling to here and having its default behavior
  prevented."
  [element]
  (let [target (.-target element)
        button (if (or (= (.-tagName target) "BUTTON")
                       (and (= (.-tagName target) "INPUT")
                            (= (.-type target) "submit")))
                 ;; If the clicked element was a button or an
                 ;; input[type=submit], that's the button.
                 target
                 ;; Otherwise, it's the button (if any) that
                 ;; contains the clicked element.
                 (dom/getAncestorByTagNameAndClass target "BUTTON"))]
    ;; Finally, if we found an applicable button and that
    ;; button is associated with a form which it would submit,
    ;; prevent that submission.
    (when (and button (.-form button))
      (.preventDefault element))))

(defn- hiccup-element? [x]
  (and (vector? x)
       (keyword? (first x))))

(defn hiccup?
  "Returns true if the given data structure is valid hiccup."
  [x]
  (or (hiccup-element? x)
      (and (sequential? x)
           (every? hiccup? x))))

(defn add-white-border
  "Takes a hiccup structure that has a tag, attribute map, and
  children. Returns the same hiccup structure with a white border
  style."
  [[tag attrs & children :as hiccup]]
  (if-not (and (hiccup? hiccup)
               (map? attrs))
    hiccup
    (into [tag
           (assoc-in attrs
                     [:style :border]
                     (str "5px solid rgb("
                          (rand-int 255)
                          ","
                          (rand-int 255)
                          ","
                          (rand-int 255)
                          ")"))]
          children)))

(defn flatten-hiccup-children
  "Takes a sequential collection of either hiccup elements or
  sequential collections of hiccup elements. Returns a flattened
  sequential collection of hiccup elements."
  [children]
  (reduce (fn [acc child]
            ;; if the child is a collection but is
            ;; not a hiccup element, conj its
            ;; contents into the children
            (if (and (sequential? child)
                     (not (hiccup-element? child)))
              (into acc (flatten-hiccup-children child))
              ;; otherwise, conj the child itself
              ;; into the children
              (conj acc child)))
          []
          children))

(defn dissoc-nil-react-keys
  "Takes a hiccup element and returns an updated version where its
  and all of its children's attribute maps don't have `nil` `:key`
  values."
  [hiccup]
  (if-not (hiccup-element? hiccup)
    hiccup
    (let [[tag & remainder] hiccup
          has-attr-map? (map? (first remainder))
          attrs (if has-attr-map?
                  (first remainder)
                  {})
          safe-attrs (cond-> attrs
                       (and (contains? attrs :key)
                            (nil? (:key attrs)))
                       (dissoc :key))
          children (flatten-hiccup-children (if has-attr-map?
                                              (rest remainder)
                                              remainder))
          processed-children (map dissoc-nil-react-keys children)]
      (into [tag safe-attrs] processed-children))))

(defn convert-emoji
  "Converts a text with emoji shortcodes (such as :sparkles:) into
  actual emoji unicode, such as ✨."
  [text]
  (.shortnameToUnicode js/emojione text))
