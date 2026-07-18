# Legible to Machines: Open Graph, schema.org, and a Sitemap from Datomic

Every public page this application serves has been written for an audience of one kind: a person, in a browser. But a public URL collects other readers the moment it exists. A search crawler deciding whether the page deserves an index entry and what to show for it. A chat client asked to unfurl a pasted link into a title and description. A recipe aggregator looking for structured data. These readers do not see your typography; they read your `<head>`, and until this chapter, this application's head said the same thing on every page: `<title>MyApp</title>` and one generic description.

The single-page world has a whole genre of infrastructure for this problem -- server-side rendering bolted onto client frameworks largely *because* crawlers and unfurlers cannot run an app to find out what it says. [We took the server first](02-positioning.md), so the hard part -- the content being present in the HTML -- has been true since chapter 5. What is missing is only the introduction: pages that say, in the vocabularies machines actually read, *what they are*. This chapter adds that vocabulary in four registers -- title and description, canonical URLs, Open Graph, and schema.org structured data -- and then publishes the catalog itself as a sitemap. All of it from one source of truth, because all of it is [the same pulled map the page already renders](09-recipe-domain.md).

## The plumbing: one optional map

[The layout](14-hiccup-views.md) gains one optional argument, and every page that wants to introduce itself passes it:

```clojure
(defn- base-layout
  "Base HTML5 wrapper. All pages use this — never called directly by page fns.
  `page-meta` (may be nil) makes the page legible to machines: `:title` and
  `:description` override the defaults; `:canonical` names the one true URL;
  `:robots` opts a page out of indexing; `:json-ld` is a structured-data map
  emitted as application/ld+json ,,, `:og` adds Open Graph pairs for link
  unfurlers."
  [locale page-meta & body]
  ,,,
  [:title (if-let [t* (:title page-meta)] (str t* " — MyApp") "MyApp")]
  (when-let [href (:canonical page-meta)]
    [:link {:rel "canonical" :href href}])
  (when-let [robots (:robots page-meta)]
    [:meta {:name "robots" :content robots}])
  (for [[k content] (:og page-meta)]
    [:meta {:property (str "og:" (name k)) :content content}])
  (when-let [ld (:json-ld page-meta)]
    [:script {:type "application/ld+json"}
     (h/raw (json/write-str ld))])
  ,,,)
```

`nil` means what it meant yesterday -- the defaults -- so thirty views changed by zero lines and the recipe page changed by one map. That map is built inside `recipe-detail` from two inputs it already had reach to: the pulled recipe, and the application's `:base-url` (passed down by the handler from [config](05-web-server.md), because a view should not know its own deployment address).

## Structured data is the same data

The centerpiece is the JSON-LD block, and the point of showing it is how little translation it required:

```clojure
(defn- recipe-json-ld
  "The schema.org/Recipe structured-data map.
  Built from the SAME pulled map the page renders — one source of truth,
  two audiences. ,,,"
  [recipe canonical]
  (cond-> {"@context" "https://schema.org"
           "@type" "Recipe"
           "name" (:recipe/title recipe)
           "author" {"@type" "Person"
                     "name" (author-name (:recipe/user recipe))}
           "recipeYield" (str (:recipe/servings recipe) " servings")
           "recipeIngredient" (recipe/lines (:recipe/ingredients recipe))
           "recipeInstructions" (mapv
                                  (fn [s]
                                    {"@type" "HowToStep"
                                     "text" s})
                                  (recipe/lines (:recipe/steps recipe)))
           "url" canonical}
    ,,,))
```

Look at `recipeIngredient` and `recipeInstructions`. [Chapter 9 chose](09-recipe-domain.md) to store ingredients and steps as newline-separated text *because the line was the unit of meaning* -- it is what made the diffs line diffs. That same decision now means the schema.org shapes -- an array of ingredient strings, an array of steps -- fall out of the existing `lines` helper with no parsing, no NLP, no annotation format. A decision made for version control pays off in search-result recipe cards. Data modeled on its real grain keeps being the right shape for audiences you had not met yet.

One question hangs over any `<script>` tag in this application, because [the CSP chapter](29-asset-pipeline.md) was strict on purpose: does a JSON-LD block need a CSP carve-out? No -- and the *why* is worth thirty seconds. `script-src` governs what may *execute*; a `<script type="application/ld+json">` is inert data wearing a script element's clothes, never evaluated, and so never subject to the execution policy. The strict CSP and the structured data coexist without either yielding. (Machines that read it fetch the page source; they do not run it. That is rather the theme of the chapter.)

## The versioning feature meets the crawler

Here is a problem this book *created* for itself. [Every recipe has point-in-time pages](09-recipe-domain.md) -- `/recipes/:id/at/:t` for each version, diff pages between any two. To a crawler, that is dozens of URLs with near-identical content competing with the page that matters, the classic duplicate-content trap, manufactured at scale by our proudest feature.

The web has a two-part answer, and the historical pages now carry both:

```clojure
(defn- historical-page-meta
  "The noindex + canonical-to-current pair for historical pages.
  Many point-in-time copies of one recipe must not compete with the live
  page in a search index."
  [id]
  {:robots "noindex"
   :canonical (str (config/get-config :base-url) "/recipes/" id)})
```

`noindex` says *do not list this page*; the canonical pointing at the **current** recipe says *credit whatever you learned here to that URL*. Two declarative lines per page class, and the version history is a feature for humans again instead of an SEO liability. The live recipe page, by contrast, canonicalizes to itself -- the one true URL for the content, stated explicitly so that query-string variants and future mirrors never split its identity.

Open Graph rides along in the same map -- title, type, URL, site name, first description line -- which is what turns a pasted link into a card in chat clients. What the card will not have is an image, because [the recipes have none](21-forms-validation.md); the product has no photo feature, and shipping a decorative placeholder as `og:image` would be the small dishonesty this book keeps declining. The day photos exist, one more pair goes in the map.

## The catalog, published

A sitemap is the standing invitation: every URL worth indexing, with a change date so crawlers spend their budget where things moved. Most stacks generate it -- a build step, a cron job, a plugin with opinions. Ours is a request handler, because the catalog is a database read and [the database is right there](08-datomic.md):

```clojure
(defn sitemap
  "GET /sitemap.xml — the public catalog, straight from the database.
  Every recipe URL with its lastmod (:recipe/updated-at), plus the index
  pages. No generator, no cron: the sitemap is a read like any other."
  [_request]
  ,,,
  (apply str
    (for [r (recipe/all-recipes db)]
      (entry (str "/recipes/" (:recipe/id r)) (:recipe/updated-at r))))
  ,,,)
```

`lastmod` is `:recipe/updated-at` -- maintained since chapter 9, now doing double duty. The sitemap can never be stale (it is computed from the same value every page renders from) and never needs regenerating (there is nothing generated). `robots.txt` is its companion handler: name the sitemap, allow the public surface, and fence `/dashboard` and `/admin` -- rooms a crawler could never enter anyway, but the polite fence saves everyone the 302s.

## Proof

The smoke test reads like a checklist of the four vocabularies:

```clojure
(is (str/includes? body "<title>Legible Lasagna — MyApp</title>"))
(is (str/includes? body (str "href=\"https://test.myapp.lan/recipes/" id "\" rel=\"canonical\"")))
(is (str/includes? body "property=\"og:title\""))
(is (str/includes? body "HowToStep"))
,,,
(is (str/includes? body "content=\"noindex\" name=\"robots\""))   ; the historical page
,,,
(is (str/includes? body "<lastmod>"))                             ; the sitemap
(is (str/includes? body "Sitemap: https://test.myapp.lan/sitemap.xml"))
```

and [the Lighthouse chapter](33-lighthouse.md), three chapters from here, is where these claims meet an external auditor: its SEO category checks precisely this surface -- meta description, canonical validity, crawlability -- in CI, on every build, so the introduction this chapter wrote can never silently regress.

## Trade-offs & limitations, in one place

- **Descriptions are the raw first line, markdown and all.** A `**bold**` in a recipe's opening line reaches the meta description with its asterisks on. The honest fix is a plain-text projection of the markdown AST -- [the renderer](14-hiccup-views.md) could grow one -- and it is deliberately deferred: a seam, labeled, costing a few odd characters in snippets meanwhile.
- **Only the recipe page gets the full treatment.** The browse index and [search results](23-search.md) ride the site defaults, which for list pages is nearly right anyway; per-page titles for them are an afternoon, using plumbing that now exists.
- **The sitemap is unbounded**, like [the browse read it reuses](09-recipe-domain.md) -- one `<urlset>`, no pagination. The sitemap protocol caps a file at 50,000 URLs; the catalog is nowhere near; the seam is the same seam, and it is labeled in both places.
- **JSON-LD duplicates content into every recipe response** -- ingredients and steps appear twice in the bytes. That is the protocol's design (machines should not have to parse your markup), it compresses well, and [the next chapter](31-conditional-get.md) is about to make repeat fetches of those bytes mostly stop happening anyway.

## Who shows up when you're legible

This chapter changes nothing a signed-in user can see, which makes it easy to undervalue -- until you notice whom it invites. Crawlers that now index recipes properly. Unfurlers that make shared links look like content instead of a bare domain. Aggregators that can read a recipe as a recipe. All of them anonymous, all of them repeat visitors, all of them fetching public pages over and over. Which is exactly the traffic the next chapter serves in constant time: the visitors this chapter attracted are about to be answered, most of the time, with [a 304 the database computes without rendering a byte](31-conditional-get.md).
