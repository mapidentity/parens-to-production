(ns myapp.i18n.en
  "English UI strings, keyed by namespaced keyword (e.g. :home/sign-in).
  Kept deliberately flat and data-only so it hot-reloads cleanly.")

(def translations
  "English translations."
  {;; Meta
   :meta/description
   "A recipe versioning site — fork recipes, tweak them, and see exactly what changed. Git, but for cooking."

   ;; Landing page
   :home/get-started "Get started"
   :home/email-label "Email address"
   :home/email-placeholder "you@example.com"
   :home/sign-in "Sign in"
   :home/magic-link-explanation "We'll email you a magic link to sign in. No password needed."
   :home/lead
   "Fork any recipe, make it yours, and keep every version. See the diff between \"grandma's\" and yours line by line."
   :home/tagline-1 "Git, but for cooking."
   :home/tagline-2 "Fork the recipe. Keep the lineage."
   :home/tagline-3 "Every tweak, versioned."
   :home/tagline-4 "Diff your dinner."
   :home/tagline-5 "This carbonara descends from four ancestors."
   :home/tagline-6 "Branch, taste, merge, repeat."

   ;; Auth
   :auth/check-email "Check your email"
   :auth/email-sent-to "We sent a magic link to "
   :auth/link-expires "Click the link in the email to sign in. The link expires in 15 minutes."
   :auth/safe-to-close "You can close this tab — the link will open in a new window."
   :auth/back-to-home "← Back to home"
   :auth/sign-out "Sign out"

   ;; Email
   :email/magic-link-subject "Sign in to MyApp"
   :email/magic-link-body
   "Click this link to sign in to MyApp:\n\n%s\n\nIf the link doesn't work, copy and paste it into your browser.\n\nThis link expires in 15 minutes.\n\nIf you didn't request this, you can safely ignore this email."
   :email/proposal-subject "A proposed change to your recipe"
   :email/proposal-body
   "Someone proposed a change to your recipe \"%s\".\n\nReview and merge or decline it here:\n\n%s\n\nYou are receiving this because you own the recipe."

   ;; Terms / onboarding
   :terms/welcome-title "Welcome to MyApp"
   :terms/welcome-explanation "Before you start cooking, a quick word on the house rules."
   :terms/terms-summary
   "This is a demo application from the book \"Building a Clojure/Datomic SaaS from Scratch.\" Recipes you create are public and may be forked by anyone. Be kind, don't post anything you wouldn't want shared, and have fun."
   :terms/accept-button "Agree and start cooking"
   :terms/decline-button "No thanks, log me out"

   ;; Navigation
   :nav/browse "Recipes"
   :nav/new "New recipe"
   :nav/dashboard "Dashboard"
   :nav/admin "Admin"

   ;; Recipes
   :recipe/browse-title "Recipes"
   :search/title "Search recipes"
   :search/label "Search"
   :search/placeholder "Search recipes…"
   :search/button "Search"
   :search/no-results "No recipes match your search."
   :recipe/new "New recipe"
   :recipe/edit "Edit"
   :recipe/delete "Delete"
   :recipe/delete-confirm "Delete this recipe? This cannot be undone."
   :recipe/actions "Actions"
   :common/cancel "Cancel"
   :recipe/fork "Fork"
   :recipe/fork-this "Fork this recipe"
   :recipe/login-to-fork "Sign in to fork"
   :recipe/history "Version history"
   :recipe/by "by"
   :recipe/servings "Servings"
   :recipe/ingredients "Ingredients"
   :recipe/steps "Method"
   :recipe/forked-from "Forked from"
   :recipe/original "Original recipe"
   :recipe/no-recipes "No recipes yet."
   :recipe/next-page "Next"
   :recipe/pagination "Pagination"

   ;; Photo upload
   :upload/add "Add a photo"
   :upload/replace "Replace photo"
   :upload/remove "Remove photo"
   :upload/hint
   "JPEG, PNG, or GIF, up to 5 MB. We'll scale large photos down to 2048px and make the sizes we need."
   :upload/error-empty "That file was empty."
   :upload/error-too-large "That image is too large (max 5 MB)."
   :upload/error-not-an-image "That file is not an image we can read."
   :upload/error-unsupported-type "Only JPEG, PNG, and GIF images are supported."
   :upload/error-too-many-pixels "That image has too many pixels."
   :upload/error-rate-limited "You're adding photos too quickly. Give it a minute and try again."
   :recipe/note-label "What changed? (optional)"
   :recipe/note-placeholder "e.g. Halved the salt — it was too much"
   :recipe/preview-title "Preview"
   :recipe/preview-waiting "The preview appears once the recipe has a title and servings."
   :recipe/title-label "Title"
   :recipe/description-label "Description (Markdown supported)"
   :recipe/servings-label "Servings"
   :recipe/ingredients-label "Ingredients (one per line)"
   :recipe/steps-label "Method (one step per line)"
   :recipe/save "Save recipe"
   :recipe/conflict-title "Someone else edited this recipe while you had it open."
   :recipe/conflict-body
   "Your changes below are kept. Saving again will overwrite the current version —"
   :recipe/conflict-review "review what changed"
   ;; Proposals (pull-request-style merge)
   :proposal/propose "Propose changes to the original"
   :proposal/title "Proposed changes"
   :proposal/from "Proposed from"
   :proposal/applies-cleanly "Applies cleanly"
   :proposal/conflict "Both versions changed this — pick one:"
   :proposal/keep-mine "Keep the current version"
   :proposal/take-theirs "Take the proposed version"
   :proposal/nothing-to-merge "This fork has no changes to merge back."
   :proposal/merge "Merge"
   :proposal/resolve-and-merge "Resolve conflicts and merge"
   :proposal/decline "Decline"
   :proposal/awaiting-owner "Waiting for the recipe's owner to review."
   :proposal/pending-heading "This recipe has pending suggestions from forks."
   :proposal/review-suggestion "Review a proposed change →"
   :proposal/stale-notice
   "This recipe changed while you were reviewing. The merge was recomputed — review and merge again."
   :proposal/unresolved-notice "Resolve every conflict before merging."
   :recipe/create "Create recipe"
   :recipe/forks "Forks"
   :recipe/no-forks "No forks yet — be the first."
   :recipe/updated "Updated"
   :recipe/created "Created"
   :recipe/version "Version"
   :recipe/view-this-version "View this version"
   :recipe/diff-from-previous "Changes from previous"
   :recipe/current "current"
   :recipe/initial "initial version"
   :recipe/viewing-historical "You're viewing a past version of this recipe."
   :recipe/back-to-recipe "← Back to current version"
   :recipe/changes-title "Changes"
   :recipe/no-changes "No changes between these versions."
   :recipe/legend-added "added"
   :recipe/legend-removed "removed"

   ;; Dashboard
   :dashboard/title "Dashboard"
   :dashboard/welcome "Welcome back"
   :activity/title "While you were away"
   :activity/forked-yours "forked your recipe:"
   :activity/updated-upstream "updated the original of your fork:"
   :dashboard/your-recipes "Your recipes"
   :dashboard/no-recipes "You haven't created any recipes yet."
   :dashboard/create-cta "Create your first recipe"
   :dashboard/reorder-hint "Drag the handle, or use ▲▼, to reorder."
   :dashboard/drag-handle "Drag to reorder"
   :dashboard/move-up "Move up"
   :dashboard/move-down "Move down"

   ;; Errors
   :error/title "Error"
   :error/invalid-magic-link "Invalid or expired magic link. Please request a new one."
   :error/not-found "Not found."
   :error/server-error "Something went wrong on our end. Please try again."
   ;; Form validation — one message per (field, code) from recipe/conform.
   :error/note-too-long "Keep the note under 500 characters."
   :error/title-blank "Give the recipe a title."
   :error/title-too-long "Keep the title under 200 characters."
   :error/servings-not-a-number "Servings must be a whole number."
   :error/servings-out-of-range "Servings must be between 1 and 100."
   :error/description-too-long "This description is too long."
   :error/ingredients-too-long "The ingredient list is too long."
   :error/steps-too-long "The method is too long."})
