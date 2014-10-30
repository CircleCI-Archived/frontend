(ns frontend.components.jobs
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as str]
            [frontend.components.common :as common]
            [frontend.components.plans :as plans-component]
            [frontend.components.shared :as shared]
            [frontend.state :as state]
            [frontend.stefon :as stefon]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [defrender html]]))

(defn jobs [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.jobs.page
        [:div.jobs-head
         [:h1 "Build amazing products"]
         [:h1 "with a great team"]]
        [:div.jobs-body
         [:div.jobs-types
          [:a {:href "#engineer"} [:i.fa.fa-code] [:h3 "Engineering"]]
          [:a {:href "#designer"} [:i.fa.fa-pencil] [:h3 "Design"]]
          [:a {:href "#business"} [:i.fa.fa-bar-chart-o] [:h3 "Business"]]]
         [:div.jobs-intro
          [:h2 "Who We Are"]
          [:p
           "CircleCI is a rapidly growing company with a great mission, a deeply technical team, and a focus on productivity for our customers and ourselves. We've a flat organization, a great culture, and a delightful product that's loved by thousands of developers. With significant revenue and funding, we're just getting started with taking developer tools to the next level."]]
         [:div.jobs-values
          [:h2 "Why you'll love working at CircleCI"]
          [:p
           [:strong "Our culture is customer-driven."]
           " Our customers and our founders are engineers, and most of our employees are too. We are inspired by companies like "
           [:a {:href "http://blog.alexmaccaw.com/stripes-culture"} "Stripe,"]
           " "
           [:a {:href "http://gigaom.com/2012/03/26/tales-from-the-trenches-github/"} "GitHub,"]
           " "
           [:a {:href "http://www.avc.com/a_vc/2012/02/the-management-team-guest-post-from-joel-spolsky.html"} "Fog Creek,"]
           " and "
           [:a {:href "http://www.valvesoftware.com/company/Valve_Handbook_LowRes.pdf"} "Valve."]]
          [:p
           [:strong "We have a flat organization."]
           " There are no managers, no org-chart and no hierarchy, and we plan to keep it that way for as long as possible. Everyone gets access to all company information and can be involved in decision-making. By keeping communication open and transparent, we believe everybody is able to make good decisions about the best thing to work on."]
          [:p
           [:strong
            "We have a very strong focus on productivity, and on shipping product."]
           " This means you'll have a lot of days where you finish the day with the happy glow of having achieved something. We ship often, and shape our work lives around enabling that."]
          [:p
           [:strong "We spend a lot of time talking to customers."]
           " We make it really easy for customers to reach out to us, and we reach out to them to ask for their feedback too. This means you'll probably spend significant time listening to and fixing their problems. We refer to that as \"support\", but really think of it as \"customer development\"."]
          [:p
           [:strong "We use cool technology."]
           " Our backend is written in Clojure. Everything from managing LXC containers to package management to serving our REST API is all done in Clojure. This allows us to be really productive, handle concurrency with ease, and leads to beautiful manageable code. Clojure isn't just a fancy new language, it's the most productive, powerful and elegant language we've ever used. So much so, that we're in the process of switching our frontend over to ClojureScript."]
          [:p
           [:strong
            "At a small growing company, your work has a disproportionate effect."]
           " As well as improving our customers' lives, you'll also set a precedent for everyone who works for us in the future. You get to affect both the product and the company culture. Your opinions matter and are listened to, and that's why we hire you."]
          [:p
           [:strong "There's lots to learn."]
           " We try to hire really, really high caliber people, which means you'll be able to learn a lot, and we hope to learn a lot from you too. We look for the very experienced, or the very talented. If you work at CircleCI, you know that you won't be on the team with slouches or slackers, or folks who will hold you back. (Curiously, we disproportionately hire folks with find it hard to believe they're as amazing as they are, and suffer from significant imposter syndrome. Go figure. If this matches you, we encourage you to apply anyway)."]]
         [:div.jobs-where
          [:h2 "Where We Work"]
          [:p
           [:strong "Local"]
           [:span " — "]
           "Our team is dedicated to creating the most productive environment possible. That means we ensure everyone is able to enjoy their own personal level of comfort and privacy. We work in San Francisco's beautiful Financial District, where you'll have a "
           [:a
            {:href "http://blog.circleci.com/silence-is-for-the-weak"}
            "private office"]
           ", catered lunch and lovely colleagues."]
          [:p
           [:strong "Remote"]
           [:span
            " — We have an asynchronous culture that is well suited to remote-working."]
           " We use HipChat, Hangouts and email heavily, and roughly half of our engineers work remotely. We'll bring you on-site every 10 weeks or so to work closely with your colleagues, and can do this to fit your remote life."]]
         [:div.jobs-what
          [:h2 "What we're looking for"]
          [:p
           "Obviously, this is a very developer-focused company, so the most important thing is to understand how developers work and think. Here's how we think about the type of people we like to work with:"]]
         [:div.jobs-listings
          [:div#general
           [:ul
            [:li
             "People who truly care about making developers more productive."]
            [:li
             "Folks who are easy to work with: we're not interested in solo artists, divas, know-it-alls, or assholes. We also tend to get on well with folks that do not identify with the words \"ninja\", \"pirate\", \"rock star\", or (especially) \"brogrammer\"."]
            [:li "Great communicators."]
            [:li
             "People who feel strongly about tools and the software development process."]
            [:li
             [:a
              {:href "http://blog.circleci.com/kindness-is-underrated"}
              "Pleasant people"]
             ", and good conversationalists - we'll be spending the next few years together after all."]
            [:li
             "Folks with an interest in the whole company, not just their job. Our transparent culture allows everyone to take a holistic view of the company. As a result, we really like people who enjoy getting familiar with the whole business, customers, product, culture, selling, etc."]]]

          [:div#engineer
           [:h3 [:span "Engineering roles"]]
           [:p
            "We're looking for great engineers: whether you've 10 years of experience shipping products, or are new to the industry with talent to burn and a fire in your belly, we need you to have amazing coding chops."]
           [:h5 "We'd love to see"]
           [:ul
            [:li
             "People who have used functional languages in anger (Clojure, Haskell, Scheme, Lisp, Scala, OCaml, etc)."]
            [:li "If you've built or scaled large distributed systems."]
            [:li "People who love trying new, cutting-edge things."]
            [:li
             "If you've worked on significant projects that weren't in Java, JavaScript, Ruby or PHP."]]
           [:h5 "With some of these skills"]
           [:ul
            [:li
             "Good (possibly informal) knowledge of algorithmic design and CS theory (CS degree not required)."]
            [:li "Clojure experts."]
            [:li "Experience designing elegant APIs."]
            [:li
             "Broad knowledge of our customers' important languages; Python, Node, JS, Ruby, Java, and PHP."]
            [:li "An understanding of scalable cloud and web services."]
            [:li
             "Dev-ops experience, including machine configuration and deployment on AWS."]
            [:li
             "Low-level Linux hacking knowledge (including file-systems, kernels, databases, LXC/Docker, etc)."]
            [:li
             "Experience building fat JS apps, stateful HTML5 apps, and data visualizations."]
            [:li "Good product and user experience sensibilities."]]
           [:a
            {:role "button", :href "/jobs#apply"}
            [:span "Apply for Engineering roles"]]]

          [:div#designer
           [:h3 [:span "Design roles"]]
           [:p
            "Our customers love great experiences, and so do we. We're looking for amazing designers who can provide them."]
           [:h5 "You will be responsible for"]
           [:ul
             [:li "Create simple solutions for complex challenges."]
             [:li "Considering the user's experience from the start of an idea to implementation."]
             [:li "Actively engaging with product strategy and development."]
             [:li "Collaborating between various teams with diverse skill sets."]
             [:li "Embracing an environment with increasingly tight feedback loops."]
             [:li "Prototyping, producing, and shipping your own designs."]
             [:li "Acting as a self-starter and motivating yourself to meet your own deadlines."]
             [:li "Upholding and improving upon existing brand standards and guidelines."]]
           [:h5 "We would love to see"]
           [:ul
             [:li "An aptitude in many skill sets and a hunger for learning."]
             [:li "The ability to communicate effeciently in a developer-centric environment."]
             [:li "A keen eye and a very strong sense of taste."]
             [:li "A deep understanding of web design and development best practices."]
             [:li "Work you've done for other web-based developer tools."]
             [:li "Any other interesting work you wish to share, finished or not."]
             [:li "A willingness to approach sophisticated problems."]]
           [:a
            {:role "button", :href "/jobs#apply"}
            [:span "Apply for Design roles"]]]

          [:div#business
           [:h3 [:span "Business roles"]]
           [:p
            "We're looking for developer-focused individuals to help us achieve business objectives."]
           [:h5 "You should have a few of:"]
           [:ul
            [:li "Deep and authentic knowledge of the developer space."]
            [:li
             "A technical background with the desire to learn more about the customer-facing parts of building a company."]
            [:li
             "Great analytical skills, with the ability to derive signal where others see noise."]
            [:li
             "Fantastic communication skills, writing ability and presentation skills."]
            [:li
             "An obsession with data-driven decision making, measuring results and automating repeatable processes."]
            [:li "Proven success in previous projects."]]
           [:h5
            [:strong "Dev-awareness"]
            " — Are you interested in telling customers about CircleCI? If so, we'd love to see:"]
           [:ul
            [:li
             "Anything you may have written about or put into practice related to conversion rate optimization, A/B testing, SaaS metrics, lifecycle marketing, virality, or marketing automation."]
            [:li
             "An understanding of modern, low-touch, software-driven marketing techniques."]
            [:li
             "An ability to build industry relationships with other developer-tooling companies."]
            [:li
             "A love of crafting stories and communicating them to our customers and the industry at large."]]
           [:h5
            [:strong "Dev-success"]
            " — Do you love problem solving and working with developers? If so, we'd love to talk if you:"]
           [:ul
            [:li "Have a combination of engineering and empathy."]
            [:li
             "Love talking to people, helping them solve problems, and building relationships."]
            [:li "Really want to help developers be happy and successful."]
            [:li
             "Have significant technical ability, with an understanding of the developer mindset, tools and culture."]
            [:li
             "Love to solve problems fully so that they never happen to any customer again."]
            [:li "Are supremely organized and resourceful."]
            [:li "Have reasonable to strong coding ability."]]
           [:a
            {:role "button", :href "/jobs#apply"}
            [:span "Apply for Business roles"]]]]

         [:div.jobs-how
          [:h2#apply "How to apply"]
          [:p "Send an email to " [:a {:href "mailto:jobs@circleci.com"} "jobs@circleci.com"] ", including a resume."]
          [:p
           "We're mostly interested in seeing evidence that you're great at what you do. (Don't worry if you're not convinced about your abilities, just work to convince us.) This might include great projects you've worked on, a portfolio, wonderful references, open source you've contributed to, products you've built or scaled, your grades at MIT, that you got hired by Google, or anything that hints that you might be a particularly amazing individual. We're also interested in reading about technologies you've worked with in the past, and what you did with them."]
          [:p
           "The kind of people we want to work with tend to pitch us as to why they should work at CircleCI. This is both why you're interested, and why you think you'd be a good part of the team. If all goes well we're going to spend a lot of time together, so it's worth it for both of us to discuss this in advance."]
          [:p
           "Naturally, this goes both ways. We're happy to answer any questions you have about CircleCI, where we're going, why it's an amazing place to work, and what we're doing to keep it that way."]
          [:p
           "Interested? "
           [:a {:href "mailto:jobs@circleci.com"} "Email us now!"]]]
         [:p [:strong "No recruiters, thank you."]]]]))))
