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
  "Reusable CommonMark HTML renderer instance."
  (let [^org.commonmark.renderer.html.HtmlRenderer$Builder b (-> (HtmlRenderer/builder)
                                                                 (.extensions extensions))]
    (.build b)))

(defn render
  "Render markdown string to HTML string."
  [markdown-str]
  (->> (.parse parser markdown-str)
       (.render renderer)))