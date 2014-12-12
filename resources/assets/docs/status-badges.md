<!--

title: Embeddable status badges
last_updated: July 30, 2014

-->

We provide project and branch status images, which you can embed anywhere you want: a GitHub
README, a dashboard, or anywhere else you can imagine!

Here's our current build status:
![](https://circleci.com/gh/circleci/circle.png?circle-token=3cc80b12ab3627373c76e13735b8bc00a1259b9e)</img>

The status images are also available in "shield" style, "flat" style and "flat-square" style:
![](https://circleci.com/gh/circleci/circle.svg?style=shield&circle-token=3cc80b12ab3627373c76e13735b8bc00a1259b9e)</img>
![](https://circleci.com/gh/circleci/circle.svg?style=flat&circle-token=3cc80b12ab3627373c76e13735b8bc00a1259b9e)</img>
![](https://circleci.com/gh/circleci/circle.svg?style=flat-square&circle-token=3cc80b12ab3627373c76e13735b8bc00a1259b9e)</img>

In the project settings for each of your repositories, there's a "Status Badges" section that can generate code for Markdown, rst, etc.  Or if you want to tweak them manually, how they work is straightforward:

You can use a simple image URL like this to see the status of your project's default branch:
`https://circleci.com/gh/:owner/:repo.png?circle-token=:circle-token`

`https://circleci.com/gh/:owner/:repo.svg?style=shield&circle-token=:circle-token`

For example:
`circleci/mongofinil` [badge](https://circleci.com/gh/circleci/mongofinil.png?circle-token=b14acf911433d315298235b0c2fbf7b2670a92a8)
and [shield](https://circleci.com/gh/circleci/mongofinil.svg?&style=shield&circle-token=b14acf911433d315298235b0c2fbf7b2670a92a8)

Or you can see the status of any specific branch: `https://circleci.com/gh/:owner/:repo/tree/:branch.png?circle-token=:circle-token`

`https://circleci.com/gh/:owner/:repo/tree/:branch.svg?style=shield&circle-token=:circle-token`

For example:
circleci/mongofinil master branch
[badge](https://circleci.com/gh/circleci/mongofinil/tree/master.png?circle-token=b14acf911433d315298235b0c2fbf7b2670a92a8)
and [shield](https://circleci.com/gh/circleci/mongofinil/tree/master.svg?style=shield&circle-token=b14acf911433d315298235b0c2fbf7b2670a92a8)

One small wrinkle: if your branch name includes characters that are "special" in URLs (most commonly '/') they'll need to be
[URL encoded](http://www.w3schools.com/tags/ref_urlencode.asp)
when constructing status badge URLs. For example, the badge for a branch called 'feature/nonexistent' would be at '.../tree/feature%2Fnonexistent.png'.


### Access Control

We recommend creating specific, narrowly scoped API tokens. In fact, we created a whole
class of tokens just for this purpose: tokens which give read-only access to the build
status of a single project, and nothing else. You can create and manage these tokens from
the **Project settings &gt; API Tokens** page of any project.

If you prefer, it is also possible to use a user's API token as the circle-token. These
tokens are very powerful, so only do that in a trusted environment!

(You can also link to the image without providing a circle-token, in which case the image
will only appear for people who are currently logged-in to circleci.com!)
