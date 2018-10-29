(ns frontend.components.setup-project
  (:require [frontend.async :refer [raise!]]
            [frontend.components.build-head :refer [build-config]]
            [frontend.components.pieces.button :as button]
            [frontend.components.pieces.card :as card]
            [frontend.components.pieces.dropdown :as dropdown]
            [frontend.components.pieces.popover :as popover]
            [frontend.components.pieces.spinner :refer [spinner]]
            [frontend.components.pieces.table :as table]
            [frontend.models.repo :as repo-model]
            [frontend.state :as state]
            [frontend.utils :as utils :refer-macros [defrender html]]
            [frontend.utils.vcs :as vcs]
            [frontend.utils.vcs-url :as vcs-url]
            [om.core :as om :include-macros true]))

;; Slurp is only available at runtime, so there is no great
;; way to read a file into a string in ClojureScript
;; We could store these files in circle/circle and serve them
;; but that's fairly involved approach for testing onboarding
;; For the time being, config strings are copy-pasted from:
;;   https://github.com/circleci/picard-templates
;;   Note: IntelliJ automatically adds spacing and line breaks
(def languages   ;; TODO: Replace icons with logos
  "Assumes 'name' is one word"
  [{:name "Clojure"
    :label "Clojure"
    :config-string "# Clojure CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-clojure/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      - image: circleci/clojure:lein-2.7.1\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n\n    environment:\n      LEIN_ROOT: \"true\"\n      # Customize the JVM maximum heap limit\n      JVM_OPTS: -Xmx3200m\n    \n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"project.clj\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run: lein deps\n\n      - save_cache:\n          paths:\n            - ~/.m2\n          key: v1-dependencies-{{ checksum \"project.clj\" }}\n        \n      # run tests!\n      - run: lein test"
    :icon [:i.material-icons "settings"]}
   {:name "Elixir"
    :label "Elixir"
    :config-string "# Elixir CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-elixir/ for more details\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version here\n      - image: circleci/elixir:1.4\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n    steps:\n      - checkout\n\n      # specify any bash command here prefixed with `run: `\n      - run: mix deps.get\n      - run: mix ecto.create\n      - run: mix test"
    :icon [:i.material-icons "settings"]}
   {:name "Go"
    :label "Go"
    :config-string "# Golang CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-go/ for more details\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version\n      - image: circleci/golang:1.8\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    #### TEMPLATE_NOTE: go expects specific checkout path representing url\n    #### expecting it in the form of\n    ####   /go/src/github.com/circleci/go-tool\n    ####   /go/src/bitbucket.org/circleci/go-tool\n    working_directory: /go/src/github.com/{{ORG_NAME}}/{{REPO_NAME}}\n    steps:\n      - checkout\n\n      # specify any bash command here prefixed with `run: `\n      - run: go get -v -t -d ./...\n      - run: go test -v ./..."
    :icon [:i.material-icons "settings"]}
   {:name "Gradle"
    :label "Gradle (Java)"
    :config-string "# Java Gradle CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-java/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      - image: circleci/openjdk:8-jdk\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n\n    environment:\n      # Customize the JVM maximum heap limit\n      JVM_OPTS: -Xmx3200m\n      TERM: dumb\n    \n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"build.gradle\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run: gradle dependencies\n\n      - save_cache:\n          paths:\n            - ~/.m2\n          key: v1-dependencies-{{ checksum \"build.gradle\" }}\n        \n      # run tests!\n      - run: gradle test\n\n\n\n"
    :icon [:i.material-icons "settings"]}
   {:name "Maven"
    :label "Maven (Java)"
    :config-string "# Java Maven CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-java/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      - image: circleci/openjdk:8-jdk\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n\n    environment:\n      # Customize the JVM maximum heap limit\n      MAVEN_OPTS: -Xmx3200m\n    \n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"pom.xml\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run: mvn dependency:go-offline\n\n      - save_cache:\n          paths:\n            - ~/.m2\n          key: v1-dependencies-{{ checksum \"pom.xml\" }}\n        \n      # run tests!\n      - run: mvn integration-test\n\n"
    :icon [:i.material-icons "settings"]}
   {:name "JavaScript"
    :label "Node"
    :config-string "# Javascript Node CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-javascript/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      - image: circleci/node:7.10\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/mongo:3.4.4\n\n    working_directory: ~/repo\n\n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"package.json\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run: yarn install\n\n      - save_cache:\n          paths:\n            - node_modules\n          key: v1-dependencies-{{ checksum \"package.json\" }}\n        \n      # run tests!\n      - run: yarn test\n\n\n"
    :icon [:i.material-icons "settings"]}
   {:name "PHP"
    :label "PHP"
    :config-string "# PHP CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-php/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      - image: circleci/php:7.1.5-browsers\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/mysql:9.4\n\n    working_directory: ~/repo\n\n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"composer.json\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run: composer install -n --prefer-dist\n\n      - save_cache:\n          paths:\n            - ./vendor\n          key: v1-dependencies-{{ checksum \"composer.json\" }}\n        \n      # run tests!\n      - run: phpunit"
    :icon [:i.material-icons "settings"]}
   {:name "Python"
    :label "Python"
    :config-string "# Python CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-python/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      # use `-browsers` prefix for selenium tests, e.g. `3.6.1-browsers`\n      - image: circleci/python:3.6.1\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n\n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"requirements.txt\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run:\n          name: install dependencies\n          command: |\n            python3 -m venv venv\n            . venv/bin/activate\n            pip install -r requirements.txt\n\n      - save_cache:\n          paths:\n            - ./venv\n          key: v1-dependencies-{{ checksum \"requirements.txt\" }}\n        \n      # run tests!\n      - run:\n          name: run tests\n          command: |\n            . venv/bin/activate\n            python manage.py test\n\n      - store_artifacts:\n          path: test-reports\n          destination: test-reports\n          "
    :icon [:i.material-icons "settings"]}
   {:name "Ruby"
    :label "Ruby"
    :config-string "# Ruby CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/language-ruby/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n       - image: circleci/ruby:2.4.1-node-browsers\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n\n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"Gemfile.lock\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run:\n          name: install dependencies\n          command: |\n            bundle install --jobs=4 --retry=3 --path vendor/bundle\n\n      - save_cache:\n          paths:\n            - ./venv\n          key: v1-dependencies-{{ checksum \"Gemfile.lock\" }}\n        \n      # Database setup\n      - run: bundle exec rake db:create\n      - run: bundle exec rake db:schema:load\n\n      # run tests!\n      - run:\n          name: run tests\n          command: |\n            mkdir /tmp/test-results\n            TEST_FILES=\"$(circleci tests glob \"spec/**/*_spec.rb\" | circleci tests split --split-by=timings)\"\n            \n            bundle exec rspec --format progress \\\n                            --format RspecJunitFormatter \\\n                            --out /tmp/test-results/rspec.xml \\\n                            --format progress \\\n                            \"${TEST_FILES}\"\n\n      # collect reports\n      - store_test_results:\n          path: /tmp/test-results\n      - store_artifacts:\n          path: /tmp/test-results\n          destination: test-results"
    :icon [:i.material-icons "settings"]}
   {:name "Scala"
    :label "Scala"
    :config-string "# Scala CircleCI 2.0 configuration file\n#\n# Check https://circleci.com/docs/2.0/sample-config/ for more details\n#\nversion: 2\njobs:\n  build:\n    docker:\n      # specify the version you desire here\n      - image: circleci/openjdk:8-jdk\n      \n      # Specify service dependencies here if necessary\n      # CircleCI maintains a library of pre-built images\n      # documented at https://circleci.com/docs/2.0/circleci-images/\n      # - image: circleci/postgres:9.4\n\n    working_directory: ~/repo\n\n    environment:\n      # Customize the JVM maximum heap limit\n      JVM_OPTS: -Xmx3200m\n      TERM: dumb\n    \n    steps:\n      - checkout\n\n      # Download and cache dependencies\n      - restore_cache:\n          keys:\n          - v1-dependencies-{{ checksum \"build.sbt\" }}\n          # fallback to using the latest cache if no exact match is found\n          - v1-dependencies-\n\n      - run: cat /dev/null | sbt test:compile\n\n      - save_cache:\n          paths:\n            - ~/.m2\n          key: v1-dependencies--{{ checksum \"build.sbt\" }}\n        \n      # run tests!\n      - run: cat /dev/null | sbt test:test"
    :icon [:i.material-icons "settings"]}
   ;; TODO: Update 'Other' (probably with Java Maven)
   {:name "Other"
    :label "Other"
    :config-string "version: 2.0\n\nreferences:\n  \n  container_config: &container_config\n    docker:\n      - image: clojure:lein-2.7.1\n    working_directory: /root/frontend\n\n  workspace_root: &workspace_root\n    /tmp/workspace\n    \n  attach_workspace: &attach_workspace\n    attach_workspace:\n      at: *workspace_root\n      \n  load_code: &load_code\n    run:\n      name: load code from workspace\n      command: |\n        # Move all files and dotfiles to current directory\n        mv /tmp/workspace/frontend/* /tmp/workspace/frontend/.[!.]* .\n      \n  jars_cache_key: &jars_cache_key\n    key: v7-dependency-jars-{{ checksum \"project.clj\" }}\n\n  js_cache_key: &js_cache_key\n    key: v6-dependency-npm-{{ checksum \"package.json\"}}-{{ checksum \"bower.json\" }}\n    \n  restore_jars: &restore_jars\n    run:\n      name: Restore m2 repo from workspace\n      command: |\n        [[ -d ~/.m2 ]] && rm -r ~/.m2\n        mv /tmp/workspace/.m2 ~/\n\n  restore_node_modules: &restore_node_modules\n    run:\n      name: Restore npm dependencies from workspace\n      command: mv /tmp/workspace/node_modules ./\n\n  restore_bower_components: &restore_bower_components\n    run:\n      name: Restore bower dependencies from workspace\n      command: mv /tmp/workspace/bower_components resources/components\n      \njobs:\n            \n  checkout_code:\n    <<: *container_config\n    steps:\n      - checkout\n      - run:\n          command: |\n            mkdir -p /tmp/workspace/frontend\n            mv * .[!.]* /tmp/workspace/frontend/\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - frontend\n\n  clojure_dependencies:\n    <<: *container_config\n    steps:\n      - *attach_workspace\n      - *load_code\n      - restore_cache:\n          <<: *jars_cache_key\n      - run:\n          name: download dependencies\n          command: |\n            [[ -f /root/.m2/.circle_cache ]] || lein deps\n      - run:\n          name: Mark m2 repo as cached\n          command: touch ~/.m2/.circle_cache\n      - save_cache:\n          <<: *jars_cache_key\n          paths:\n            - /root/.m2\n      - run:\n          name: Move m2 repo to workspace\n          command: mv /root/.m2 /tmp/workspace/\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - .m2\n\n  npm_bower_dependencies:\n    <<: *container_config\n    docker:\n      - image: node:4.8.3\n    steps:\n      - *attach_workspace\n      - *load_code\n      - restore_cache:\n          <<: *js_cache_key\n      - run:\n          name: download dependencies\n          command: |\n            if [ ! -d node_modules -o ! -d resources/components ]; then\n              set -exu\n              npm install\n              node_modules/bower/bin/bower --allow-root install\n            fi\n      - save_cache:\n          <<: *js_cache_key\n          paths:\n            - /root/frontend/node_modules\n            - /root/frontend/resources/components\n      - run:\n          name: Move dependencies to workspace\n          command: |\n            mv node_modules /tmp/workspace/\n            mv resources/components /tmp/workspace/bower_components\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - node_modules\n            - bower_components\n          \n  clojure_test:\n    <<: *container_config\n    steps:\n      - *attach_workspace\n      - *load_code\n      - *restore_jars\n      - run:\n          name: lein test\n          command: lein test\n    \n  cljs_test:\n    docker:\n      - image: docker:latest\n        environment:\n          IMAGE_TAG: ci-testing-image\n    working_directory: /root/frontend\n    steps:\n      - setup_remote_docker:\n          reusable: true\n      # This is necessary because the docker:latest image doesn't have gnu tar\n      - run:\n          name: Install tar\n          command: |\n            set -x\n            apk update\n            apk add tar\n      - *attach_workspace\n      - *load_code\n      - *restore_jars\n      - *restore_bower_components\n      - *restore_node_modules\n      - run:\n          name: Restore compiled cljs from workspace\n          command: |\n            set -exu\n            mkdir -p resources/public/cljs\n            mv /tmp/workspace/compiled-cljs/test resources/public/cljs/\n      - run:\n          name: run tests\n          command: |\n            set -x\n            docker build -t \"$IMAGE_TAG\" ci-testing-image\n            CONTAINER_NAME=$(docker create $IMAGE_TAG bash -c 'cd /root/frontend && lein doo chrome-headless test once')\n            docker cp . \"$CONTAINER_NAME:/root/frontend\"\n            docker cp /root/.m2/. \"$CONTAINER_NAME:/root/.m2\"\n            docker start -a $CONTAINER_NAME\n          \n  cljsbuild_whitespace:\n    <<: *container_config\n    steps:\n      - *attach_workspace\n      - *load_code\n      - *restore_jars\n      - run:\n          name: cljsbuild whitespace\n          command: lein cljsbuild once whitespace\n      - run:\n          name: Move compiled cljs to workspace\n          command: |\n            set -exu\n            mkdir -p /tmp/workspace/compiled-cljs\n            mv resources/public/cljs/whitespace /tmp/workspace/compiled-cljs/\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - compiled-cljs/whitespace\n\n  cljsbuild_production:\n    <<: *container_config\n    steps:\n      - *attach_workspace\n      - *load_code\n      - *restore_jars\n      - run:\n          name: cljsbuild production\n          command: lein cljsbuild once production\n      - run:\n          name: Move compiled cljs to workspace\n          command: |\n            set -exu\n            mkdir -p /tmp/workspace/compiled-cljs\n            mv resources/public/cljs/production /tmp/workspace/compiled-cljs/\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - compiled-cljs/production\n\n  cljsbuild_test:\n    <<: *container_config\n    steps:\n      - *attach_workspace\n      - *load_code\n      - *restore_jars\n      - run:\n          name: cljsbuild test\n          command: lein cljsbuild once test\n      - run:\n          name: Move compiled cljs to workspace\n          command: |\n            set -exu\n            mkdir -p /tmp/workspace/compiled-cljs\n            mv resources/public/cljs/test /tmp/workspace/compiled-cljs/\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - compiled-cljs/test\n          \n  precompile_assets:\n    <<: *container_config\n    steps:\n      - *attach_workspace\n      - *load_code\n      - *restore_jars\n      - *restore_node_modules\n      - *restore_bower_components\n      - run:\n          name: Restore compiled cljs from workspace\n          command: |\n            set -exu\n            mkdir -p resources/public/cljs\n            mv /tmp/workspace/compiled-cljs/* resources/public/cljs/\n      - run:\n          name: Install node/npm\n          command: |\n            curl -sL https://deb.nodesource.com/setup_4.x | bash -\n            apt-get install -y nodejs      \n      - run:\n          name: precompile assets\n          command: |\n            source ~/.bashrc\n            lein run -m frontend.tasks.http/precompile-assets\n      - run:\n          name: Move compiled assets to workspace\n          command: mv resources /tmp/workspace/assets\n\n      - persist_to_workspace:\n          root: *workspace_root\n          paths:\n            - assets\n            \n  deploy:\n    docker:\n      - image: python:2.7\n        environment:\n          BUILD_JSON_PATH: integration-test-build.json\n    working_directory: /root/frontend\n    steps:\n      - *attach_workspace\n      - *load_code\n      - run:\n          name: Restore compiled assets from workspace\n          command: |\n            rm -r resources\n            mv /tmp/workspace/assets resources\n      - add-ssh-keys\n      - run:\n          name: Install AWS CLI\n          command: pip install awscli\n      - run:\n          name: Install jq\n          command: |\n            apt-get update && apt-get install jq\n      - run:\n          name: deploy and trigger integration tests\n          command: |\n            set -ex\n            ssh-add -D\n            script/deploy.sh\n            if [[ \"${CIRCLE_BRANCH}\" == \"master\" ]]\n            then\n            curl https://api.rollbar.com/api/1/deploy/ \\\n            --form access_token=$ROLLBAR_ACCESS_TOKEN \\\n            --form environment=production \\\n            --form revision=$CIRCLE_SHA1 \\\n            --form local_username=$CIRCLE_USERNAME\n            fi\n      - run:\n          name: Wait for deploy/integration tests to complete\n          command: ./script/wait-for-deploy.sh\n\nworkflows:\n  version: 2\n\n  build_test_deploy:\n    jobs:\n      - checkout_code\n      - clojure_dependencies:\n          requires:\n            - checkout_code\n      - npm_bower_dependencies:\n          requires:\n            - checkout_code\n      - clojure_test:\n          requires:\n            - clojure_dependencies\n            - checkout_code\n      - cljs_test:\n          requires:\n            - clojure_dependencies\n            - npm_bower_dependencies\n            - checkout_code\n            - cljsbuild_test\n      - cljsbuild_test:\n          requires:\n            - clojure_dependencies\n            - checkout_code\n      - cljsbuild_whitespace:\n          requires:\n            - clojure_dependencies\n            - checkout_code\n      - cljsbuild_production:\n          requires:\n            - clojure_dependencies\n            - checkout_code\n      - precompile_assets:\n          requires:\n            - clojure_dependencies\n            - npm_bower_dependencies\n            - cljsbuild_whitespace\n            - cljsbuild_production\n            - checkout_code\n      - deploy:\n          requires:\n            - precompile_assets\n            - cljs_test\n            - clojure_test\n            - checkout_code\n      \ndependencies:\n  cache_directories:\n    - \"~/.cache/bower\"\n  post:\n    - node_modules/bower/bin/bower install || (sleep 2; node_modules/bower/bin/bower install)\n    - \"[[ -d resources/components ]] || node_modules/bower/bin/bower install\"\n    - lein cljsbuild once whitespace test production\n    - lein run -m frontend.tasks.http/precompile-assets\n\n\ntest:\n  pre:\n    - git grep --color TODO | cat\n  post:\n    - lein doo chrome test once\n\ndeployment:\n  deploy:\n    branch: /(?!master).+/\n    commands:\n      - script/deploy.sh\n  track-master:\n    branch: master\n    commands:\n      - script/deploy.sh\n      - curl https://api.rollbar.com/api/1/deploy/\n          --form access_token=$ROLLBAR_ACCESS_TOKEN\n          --form environment=production\n          --form revision=$CIRCLE_SHA1\n          --form local_username=$CIRCLE_USERNAME\n"
    :icon [:i.material-icons "settings"]}])

(defn- languages-list
  []
  languages)

(defn- language
  [language-list language-name]
  (->> language-list
      (filter #(= (:name %) language-name))
      first))

(defn- language-name
  [language]
  (:name language))

(defn- language-label
  "Copy associated with language name
   e.g. 'Java' would be name, 'Java (Maven)' would be label"
  [language]
  (:label language))

(defn- language-config
  [language]
  (:config-string language))

(defn- language-icon
  [language]
  (:icon language))

(defn- language-tiles
  [set-selected-language-name selected-language-name languages]
  (->> languages
    (map (fn [language]
           (let [language-name (language-name language)
                 language-label (language-label language)]
             [:li.radio
              [:input
               {:type "radio"
                :id language-name
                :checked (= selected-language-name language-name)
                :on-change #(set-selected-language-name language-name)}]
              [:label {:for language-name}
               (:icon language) " " language-label]])))))

; TODO: Abstract for general use in app
; https://stackoverflow.com/a/30810322
(defn- copy-to-clipboard [config-string]
  [:div
   (button/button
     {:kind :primary
      :fixed? true
      :on-click (fn []
                  ; Select the hidden text area.
                  (-> js/document
                    (.querySelector ".hidden-config")
                    .select)
                  ; Copy to clipboard.
                  (.execCommand js/document "copy"))}
     "Copy to clipboard")
   [:textarea.hidden-config
    {:value config-string}]])

(defn- start-build-button [selected-project owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [org-login (:username selected-project)
              repo-name (:name selected-project)
              vcs-type (:vcs_type selected-project)]
          (button/managed-button {:title "This project is not building on CircleCI. Clicking will cause CircleCI to start building the project."
                                  :data-spinner true
                                  :fixed? true
                                  :success-text "Sent"
                                  :on-click #(do
                                               (raise! owner [:followed-repo (assoc selected-project :login org-login
                                                                                                     :type vcs-type)])

                                               ((om/get-shared owner :track-event)
                                                 {:event-type :build-project-clicked
                                                  :properties {:project-vcs-url (repo-model/id selected-project)
                                                               :repo repo-name
                                                               :org org-login}}))
                                  :kind :primary}
            "Start building"))))))

(defn instructions-table
  [selected-config-string selected-project]
  [{:number "1."
    :text [:p
           "Create a folder named " [:code ".circleci"] " and add a file named" [:code "config.yml"]
           " to it (so that the filepath is " [:code ".circleci/config.yml"] ")."]
    :button ""}
   {:number "2."
    :text [:p "Populate the " [:code "config.yml"] " with the contents of the sample .yml (shown below)."]
    :button (copy-to-clipboard selected-config-string)}
   {:number "3."
    :text [:p "Update the sample .yml to reflect your project's configuration."]
    :button ""}
   {:number "4."
    :text [:p "Push this change up to " (vcs/name-capitalization (:vcs_type selected-project)) "."]
    :button ""}
   {:number "5."
    :text [:p "Start building! This will launch your project on CircleCI and make our webhooks listen for updates to your work."]
    :button (om/build start-build-button selected-project)}])

(defrender sample-yml
  [{:keys [selected-config-string selected-project]} owner]
  (html
    [:div
     [:h2 "Next Steps"]
     [:p "You're almost there! We're going to walk you through setting up a configuration file, committing it, and turning on our listener so that CircleCI can test your commits."]
     [:p "Want to skip ahead? Jump right " [:a {:target "_blank"
                               :href utils/platform-2-0-docs-url}
                           "into our documentation"]  ", set up a .yml file, and kick off your build with the button below."]
     [:.checklist
      (om/build table/table {:rows (instructions-table selected-config-string selected-project)
                             :key-fn :test-table
                             :columns [{:header ""
                                        :cell-fn :number}
                                       {:header ""
                                        :cell-fn :text}
                                       {:header ""
                                        :cell-fn :button}]})]
     [:br]
     [:p "If you start building before you've added the .yml, there's no need to panic. Our platform will try to run Circle 1.0 build using inference. To get back on track, simply add the .yml and your configuration will do the rest."]
     (when selected-config-string
       [:div
        [:h2 "Sample .yml File"]
        (om/build build-config {:config-string selected-config-string})])]))

(defn- select-language
  [{:keys [selected-project selected-language-name set-submit-disabled? set-selected-language-name]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:selected-language-name (or selected-language-name "Other")
       :submit-disabled? false})
    om/IWillReceiveProps
    (will-receive-props [_ new-props]
      (let [new-selected-language (:selected-language-name new-props)]
        (when (not= selected-language-name new-selected-language)
          (om/set-state! owner :selected-language-name new-selected-language))))

    om/IRenderState
    (render-state [_ {:keys [selected-language-name submit-disabled?]}]
      (let [languages (languages-list)
            selected-config-string (language-config (language languages selected-language-name))
            submit-button-text (if submit-disabled? "Submitted" "Submit")]
        (html
          [:div
           [:.languages
            [:h2 "Language"]
            [:.language-list
             [:ul
              (language-tiles set-selected-language-name selected-language-name languages)]]
            (if (= selected-language-name "Other")
              [:.missing-languages
               (when-not submit-disabled?
                 [:span "Request a language: "
                  [:input
                   {:class "requested-language"
                    :type "text"}]])

               (button/button {:on-click #(do
                                            ((om/get-shared owner :track-event)
                                              {:event-type :submit-language-clicked
                                               :properties (assoc selected-project
                                                             :calculated-language selected-language-name
                                                             :requested-language (-> js/document
                                                                                   (.querySelector ".requested-language")
                                                                                   .-value))})
                                            (om/set-state! owner :submit-disabled? true))
                               :kind :primary
                               :disabled? submit-disabled?}
                 submit-button-text)
               (when submit-disabled?
                 [:p.thank-you "Thank you for your feedback! Below is a sample yml to get you started."])])]
           (om/build sample-yml {:selected-config-string selected-config-string
                                 :selected-project selected-project})])))))

(defn- platform-2-0 [{:keys [select-language-args]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         (om/build select-language select-language-args)]))))

(defn- platform-1-0 [{:keys [selected-project]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        (let [org-login (:username selected-project)
              repo-name (:name selected-project)
              vcs-type (:vcs_type selected-project)]
          [:div.one-platform
           [:div.experienced
            [:h2 "Want to skip ahead?"]
            [:p "Jump right " [:a {:href utils/platform-1-0-docs-url
                                   :target "_blank"} "into our documentation"]
               ", set up a .yml, and kick off your build with the button below."]
            [:p "Note: If you start a build without adding a .yml file, we'll use inference."]
            [:p "You can always add a .yml file to your project at a later date."]]

           [:div.inference
            [:h2 "New here?"]
            [:p "We'll try to run your build without a .yml in place, using inference."]
            [:p "Just click on the button below to kick off a build."]]

           [:br]
           (om/build start-build-button selected-project)
           ])))))

(def ^:private platform-osx-default {:os :osx :platform "1.0"})
(def ^:private platform-linux-default {:os :linux :platform "2.0"})

(defn- default-platform [project]
  (if (repo-model/likely-osx-repo? project)
    platform-osx-default
    platform-linux-default))

(defn- select-platform
  [{:keys [os platform set-platform]}]
  (let [maybe-osx-disabled-tooltip (if (= os :osx)
                                     #(popover/popover {:title nil
                                                        :body [:span "We're currently working on OS X in 2.0 platform."]
                                                        :placement :top
                                                        :trigger-mode :hover}
                                        %)
                                     identity)]
    [:div.platform
     [:h2 "Platform"]
     [:ul
      [:div.platform-item
       [:li.radio
        (maybe-osx-disabled-tooltip
          [:div
           [:input
            {:type "radio"
             :id "2.0"
             :disabled (= os :osx)
             :checked (= platform "2.0")
             :on-change #(set-platform "2.0")}]
           [:label {:for "2.0"}
            [:p "2.0 " [:span.new-badge]]
            [:p "The new version of our platform enables the most power, flexibility and control available to speed up your builds. This version offers more for all and is especially ideal for Docker projects. "
             [:a {:href utils/platform-2-0-docs-url
                  :target "_blank"}
              "Learn more"]]
            [:p "Getting started on 2.0 involves reviewing sample configurations to help you compose your own config file."]]])]]
      [:div.platform-item
       [:li.radio
        [:input
         {:type "radio"
          :id "1.0"
          :checked (= platform "1.0")
          :on-change #(set-platform "1.0")}]
        [:label {:for "1.0"}
         [:p "1.0"]
         [:p "The classic version of our platform offers all the standard features over 100,000 developers have adopted."]
         [:br]
         [:p "Getting started on 1.0 involves first an automated attempt to infer your settings and if that fails, it requires setting up a basic configuration file."]]]]]]))

(defn- select-operating-system
  [{:keys [os set-os]}]
  [:div
   [:h2 "Operating System"]
   [:ul.os
    [:li.radio
     [:input
      {:type "radio"
       :id "linux"
       :checked (= os :linux)
       :on-change #(set-os platform-linux-default)}]
     [:label {:for "linux"}
      [:i.fa.fa-linux.fa-lg]
      " Linux"]]
    [:li.radio
     [:input
      {:type "radio"
       :id "osx"
       :checked (= os :osx)
       :on-change #(set-os platform-osx-default)}]
     [:label {:for "osx"}
      [:i.fa.fa-apple.fa-lg]
      " OS X"]]]])

(defn- setup-options [{:keys [selected-project set-os]} owner]
  (reify
    om/IInitState
    (init-state [_]
      (merge (default-platform selected-project)
        {:selected-language-name (:language selected-project)}))

    om/IWillReceiveProps
    (will-receive-props [_ new-props]
      (let [new-selected-project (:selected-project new-props)]
        (when (and (not= (repo-model/id selected-project)
                         (repo-model/id new-selected-project)))
          (om/set-state! owner (default-platform new-selected-project))
          (om/set-state! owner :selected-language-name (:language selected-project)))))

    om/IRenderState
    (render-state [_ {:keys [platform os selected-language-name]}]
      (let [set-selected-language-name #(om/set-state! owner :selected-language-name %)
            set-submit-disabled? #(om/set-state! owner :submit-disabled? %)
            set-os #(om/set-state! owner %)
            set-platform #(om/set-state! owner :platform %)]

        (html
          [:form.platform-selector
           (select-operating-system {:os os
                                     :set-os set-os})
           (select-platform {:os os
                             :platform platform
                             :set-platform set-platform})
           (if (= platform "1.0")
             (om/build platform-1-0 {:selected-project selected-project})
             (om/build platform-2-0 {:select-language-args {:selected-project selected-project
                                                            :set-submit-disabled? set-submit-disabled?
                                                            :set-selected-language-name set-selected-language-name
                                                            :selected-language-name selected-language-name}}))])))))

(defn- projects-dropdown [{:keys [projects selected-project]} owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:.projects-dropdown
         [:h2 "Repository"]
         (if (nil? projects)
           [:.spinner-placeholder (spinner)]
           (dropdown/dropdown
             {:label "Repo"
              :on-change #(raise! owner [:setup-project-select-project %])
              :default-text "Select a repository"
              :value (repo-model/id selected-project)
              :options (->> projects
                         vals
                         (sort-by #(vcs-url/project-name (:vcs_url %)))
                         (map (fn [repo]
                                (let [repo-id (repo-model/id repo)]
                                  [repo-id (vcs-url/project-name (:vcs_url repo))]))))}))]))))

(defrender setup-project [data owner]
  (let [projects (get-in data state/setup-project-projects-path)
        selected-project (get-in data state/setup-project-selected-project-path)]
    (html
      [:div#setup-project
       (card/titled {:title "Setup Project"}
         (html
           [:div
            [:p "CircleCI helps you ship better code, faster. Let's add some projects on CircleCI. To kick things off, you'll need to choose a project to build. We'll start a new build for you each time someone pushes a new commit."]
            (om/build projects-dropdown {:projects projects
                                         :selected-project selected-project})])
         (when selected-project
           [:div
            (om/build setup-options {:selected-project selected-project})]))])))
