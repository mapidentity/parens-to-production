(ns myapp.web.markdown
  "CommonMark markdown-to-HTML rendering with GFM tables extension.
  Used for rendering user-authored recipe descriptions and the terms page."
  (:import
    [org.commonmark.parser Parser]
    [org.commonmark.renderer.html HtmlRenderer]
    [org.commonmark.ext.gfm.tables TablesExtension]))

(set! *warn-on-reflection* true)

(def ^:private extensions
  "CommonMark extensions to enable (GFM tables)."
  [(TablesExtension/create)])

(def ^:private ^Parser parser
  "Reusable CommonMark parser instance."
  (let [^org.commonmark.parser.Parser$Builder b (-> (Parser/builder)
                                                    (.extensions extensions))]
    (.build b)))

(def ^:private ^HtmlRenderer renderer
  "Reusable CommonMark HTML renderer instance.

  `escapeHtml` and `sanitizeUrls` are the load-bearing security settings:
  the input is user-authored recipe descriptions, and CommonMark's default
  renderer passes raw inline HTML (`<script>`, `<img onerror>`) and
  dangerous URL schemes (`javascript:`) through verbatim. We escape inline
  HTML and strip unsafe URL schemes here so the renderer's output is safe to
  emit with `h/raw`. The strict CSP is defense-in-depth behind this, not the
  primary control — see `myapp.web.markdown` callers in `views.clj`."
  (let [^org.commonmark.renderer.html.HtmlRenderer$Builder b (-> (HtmlRenderer/builder)
                                                                 (.extensions extensions)
                                                                 (.escapeHtml true)
                                                                 (.sanitizeUrls true))]
    (.build b)))

(defn render
  "Render markdown string to HTML string."
  [markdown-str]
  (->> (.parse parser markdown-str)
       (.render renderer)))