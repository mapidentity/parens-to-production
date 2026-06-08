(ns hooks.missing-docstring
  (:require [clj-kondo.hooks-api :as api]))

(defn check-defn- [{:keys [node]}]
  (let [children (rest (:children node))
        name-node (first children)
        after-name (second children)]
    (when (and name-node after-name
               (not (api/string-node? after-name)))
      (api/reg-finding!
       (assoc (meta name-node)
              :message "Missing docstring."
              :type :missing-docstring)))))
