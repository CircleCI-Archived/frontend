(ns circle.backend.build.test-config
  (:require [clojure.string :as str])
  (:use midje.sweet)
  (:use circle.backend.build.config)
  (:use [circle.model.build :only (checkout-dir valid? validate successful?)])
  (:use [circle.backend.action.bash :only (bash remote-bash-build)])
  (:require [circle.backend.build.run :as run])
  (:require [circle.backend.build.test-utils :as test])
  (:require [circle.model.project :as project])
  (:require [circle.model.spec :as spec])
  (:require [circle.backend.build.inference :as inference])
  (:require circle.init)
  (:use [arohner.utils :only (inspect)])
  (:use [circle.util.predicates :only (ref?)]))

(fact "parse-action-map works"
  (against-background
    ;;; Stub checkout dir to be /usr. Later, we will pass "bin" to :pwd, so the ls will run in /usr/bin
    (checkout-dir anything) => "/")
  (let [dir "usr"
        cmd {(keyword "ls") {:environment {:CIRCLE_ENV "production", :SWANK "true"}
                             :pwd dir}}
        b (test/minimal-build :actions [(parse-action-map cmd)])
        expected-pwd (format "%s/%s" (checkout-dir b) dir)
        _ (run/run-build b)]
    (successful? b) => truthy

    ;; ssh into localhost, ls /usr/bin. Assert the output of ls
    ;; contains some well-known files, proving that the :pwd was set
    ;; properly
    (-> @b :action-results (first) :out (first) :message (str/split #"\n") (set) (contains? "cd"))))

(fact "load-job works"
  (load-job test/circle-config :build) => truthy)

(fact "template/find works keyword name"
  (circle.backend.build.template/find :build) => truthy)

(fact "template/find works with strings"
  (circle.backend.build.template/find "build") => truthy)

(fact "build-from-url works"
  (let [project (test/test-project)
        vcs_revision "78f58846a049bb6772dcb298163b52c4657c7d45"
        b (build-from-url (-> project :vcs_url)
                             :vcs-revision vcs_revision
                             :job-name :build)]
    b => ref?
    @b => (contains {:vcs_url string?
                     :vcs_revision "78f58846a049bb6772dcb298163b52c4657c7d45"
                     :node anything
                     :parents (contains string?)
                     :subject string?
                     :committer_date #"\d+"
                     :build_num pos?
                     :vcs-private-key string?})

    (-> @b :actions) => () ; no steps inferred

    (validate @b) => nil))

(fact "build-from-url :job-name :deploy works"
  (let [project (test/circle-project)
        b (build-from-url (-> project :vcs_url)
                         :job-name :deploy)]
    @b => (contains {:vcs_url "https://github.com/arohner/CircleCI"
                     :job-name :deploy})
    (-> @b :actions) => (contains [(contains {:name "start nodes"
                                              :type :infrastructure
                                              :source :template})
                                   (contains {:name #"nginx"
                                              :source :spec})]
                                  :gaps-ok)))

(fact "build-from-url :infer flag works"
  (let [project (test/circle-project)

        b (build-from-url (-> project :vcs_url)
                          :infer true)]
    (-> @b :actions) =not=> (contains {:name #"nginx"})
    (-> @b :actions) =not=> (contains {:source :spec})

    (-> @b :actions) =contains=> {:name #"bundle install"
                                  :source :inferred}))





(fact "build-from-url builds from database"
  (let [project (test/partially-inferred-project)
        b (build-from-url (-> project :vcs_url))]
    (-> @b :actions) =contains=> {:name "echo a" :type :test :source :spec}
    (-> @b :actions) =not=> (contains {:type :setup :source :inferred})))



(fact "build-from-json works"
  (let [build (build-from-json test/circle-dummy-project-json)]
    (ref? build) => true
    (-> @build :vcs_revision) => "78f58846a049bb6772dcb298163b52c4657c7d45"))

(fact "build-from-url works for yaml configs"
  (build-from-url "https://github.com/arohner/CircleCI") => ref?)

(fact "parse-spec-actions support different kinds of newline"
  (-> :setup ((parse-spec-actions {:setup "1\n2"}))) => (maps-containing {:name "1"} {:name "2"})
  (-> :setup ((parse-spec-actions {:setup "1\r\n2"}))) => (maps-containing {:name "1"} {:name "2"}))


(fact "circle is not inferred"
  ;; not a real "test", but currently circle shouldn't be inferred,
  ;; and causes hard to find test failures when it's not.
  (let [json test/circle-github-json
        p (project/get-by-url (-> json :repository :url))]
    (-> p :inferred) => falsey
    (-> (build-from-json json) (deref) :job-name) => :build))

(fact "build loads the node and slurps the ssh keys"
  ;; The circle.yml contains :private-key, :public-key. Verify they were slurped.
  (let [build (build-from-json test/circle-github-json)]
    (-> @build :node) => map?
    (-> @build :node :username) => "ubuntu"
    (-> @build :node :public-key) => #"^ssh-rsa"
    (-> @build :node :private-key) => #"BEGIN RSA PRIVATE KEY"
    (validate @build) => nil))