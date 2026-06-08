(ns myapp.i18n-test
  "Tests for the i18n translation function.
  Covers locale lookup, the fallback chain, and key parity between Dutch and
  English."
  (:require
    [clojure.test :refer [deftest is]]
    [myapp.i18n :as i18n]
    [myapp.i18n.en]
    [myapp.i18n.nl]))

(deftest t-returns-dutch-for-nl-locale (is (= "Inloggen" (i18n/t :nl :home/sign-in))))

(deftest t-returns-english-for-en-locale (is (= "Sign in" (i18n/t :en :home/sign-in))))

(deftest t-falls-back-to-default-locale-for-unknown-locale
  ;; default-locale is :en, so an unsupported locale falls back to English.
  (is (= "Sign in" (i18n/t :fr :home/sign-in))))

(deftest t-falls-back-to-key-name-for-missing-key
  (is (= "nonexistent" (i18n/t :en :home/nonexistent))))

(deftest both-locales-have-same-keys
  (let [nl-keys (set (keys myapp.i18n.nl/translations))
        en-keys (set (keys myapp.i18n.en/translations))]
    (is (= nl-keys en-keys) "Dutch and English should define the same set of keys")))

(deftest detect-locale-english-browser (is (= :en (i18n/detect-locale "en-US,en;q=0.9"))))

(deftest detect-locale-dutch-browser (is (= :nl (i18n/detect-locale "nl-NL,nl;q=0.9,en;q=0.8"))))

(deftest detect-locale-respects-quality (is (= :en (i18n/detect-locale "nl;q=0.8,en;q=0.9"))))

(deftest detect-locale-unsupported-language-returns-nil
  (is (nil? (i18n/detect-locale "de-DE,de;q=0.9"))))

(deftest detect-locale-nil-returns-nil (is (nil? (i18n/detect-locale nil))))

(deftest detect-locale-empty-string-returns-nil (is (nil? (i18n/detect-locale ""))))

(deftest detect-locale-malformed-header-returns-nil
  (is (nil? (i18n/detect-locale ";;;garbage!!!"))))
