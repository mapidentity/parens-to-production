(ns myapp.web.markdown-test
  "Tests for CommonMark rendering.
  Covers paragraphs, headings, GFM tables, links, and edge cases."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [myapp.web.markdown :as markdown]))

(deftest renders-paragraph
  (is (str/includes? (markdown/render "Hello world") "<p>Hello world</p>")))

(deftest renders-heading (is (str/includes? (markdown/render "# Title") "<h1>Title</h1>")))

(deftest renders-bold-and-italic
  (let [html (markdown/render "**bold** and *italic*")]
    (is (str/includes? html "<strong>bold</strong>"))
    (is (str/includes? html "<em>italic</em>"))))

(deftest renders-gfm-table
  (let [md "| A | B |\n|---|---|\n| 1 | 2 |"
        html (markdown/render md)]
    (is (str/includes? html "<table>"))
    (is (str/includes? html "<td>1</td>"))))

(deftest renders-links
  (let [html (markdown/render "[MyApp](https://myapp.nl)")]
    (is (str/includes? html "<a href=\"https://myapp.nl\">MyApp</a>"))))

(deftest empty-string-no-exception
  (let [html (markdown/render "")]
    (is (string? html))
    (is (str/blank? html))))
