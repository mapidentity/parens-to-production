(ns seed
  "Development seed data — a small but lively recipe graph.

  Builds a 4-deep carbonara fork lineage (so a leaf genuinely \"descends from
  4 ancestors\"), a couple of side forks, and some standalone recipes. Several
  recipes get real edit history (multiple transactions) so the version timeline
  and diffs have something to show.

  Usage from the dev REPL:  (seed/seed!)"
  (:require
    [datomic.api :as d]
    [myapp.db.core :as db]
    [myapp.recipe.core :as recipe]
    [myapp.time :as time])
  (:import
    [java.util UUID]))

(defn- user!
  "Upsert a user by email with a display name and accepted terms; return eid."
  [conn email display-name]
  @(db/transact* conn
     [{:db/id (str "u-" email)
       :user/id (UUID/randomUUID)
       :user/email email
       :user/display-name display-name
       :user/active? true
       :user/created-at (time/now)
       :user/terms-accepted-at (time/now)}])
  (d/q '[:find ?e . :in $ ?m :where [?e :user/email ?m]] (d/db conn) email))

(defn seed!
  "(Re)seed the operational database with recipes. Idempotent-ish: it just adds
  more recipes each run, so call it against a fresh dev DB (see user/reset-db!)."
  []
  (let [conn (db/get-connection)
        alice (user! conn "alice@myapp.lan" "Alice")
        bob (user! conn "bob@myapp.lan" "Bob")
        carol (user! conn "carol@myapp.lan" "Carol")
        dave (user! conn "dave@myapp.lan" "Dave")
        ;; --- root recipe, with three versions of edit history ---
        carbonara (recipe/create! conn alice
                    {:title "Classic Carbonara"
                     :description "The Roman original. No cream — *ever*."
                     :servings 2
                     :ingredients "200g spaghetti\n100g guanciale\n2 egg yolks\n50g pecorino romano\nblack pepper"
                     :steps "Boil the pasta in salted water\nCrisp the guanciale\nWhisk yolks with pecorino\nToss off the heat with a splash of pasta water\nFinish with lots of black pepper"})
        _ (recipe/update! conn alice carbonara
            {:recipe/ingredients "200g spaghetti\n100g guanciale\n2 egg yolks\n1 whole egg\n50g pecorino romano\nblack pepper"
             :recipe/description "The Roman original. No cream — *ever*. One whole egg keeps it glossy."})
        _ (recipe/update! conn alice carbonara
            {:recipe/steps "Boil the spaghetti in well-salted water\nCrisp the guanciale in a cold pan, rendering the fat\nWhisk yolks and the whole egg with the pecorino\nToss off the heat with a ladle of starchy pasta water\nFinish with a generous grind of black pepper\nServe immediately"})
        ;; --- the 4-deep lineage: peas → vegan → cashew → spicy ---
        peas (recipe/fork! conn bob carbonara)
        _ (recipe/update! conn bob peas
            {:recipe/title "Carbonara with Peas"
             :recipe/ingredients "200g spaghetti\n100g guanciale\n2 egg yolks\n1 whole egg\n50g pecorino romano\n80g fresh peas\nblack pepper"
             :recipe/description "Heresy to some, dinner to me. Peas for a little sweetness."})
        vegan (recipe/fork! conn carol peas)
        _ (recipe/update! conn carol vegan
            {:recipe/title "Vegan 'Carbonara' with Peas"
             :recipe/ingredients "200g spaghetti\n150g smoked tofu\n200ml oat cream\n3 tbsp nutritional yeast\n80g fresh peas\nblack pepper"
             :recipe/steps "Boil the spaghetti\nFry the smoked tofu until crisp\nWarm the oat cream with nutritional yeast\nToss pasta, tofu, peas and sauce\nSeason generously with black pepper"
             :recipe/description "No eggs, no guanciale — but the spirit lives on."})
        cashew (recipe/fork! conn dave vegan)
        _ (recipe/update! conn dave cashew
            {:recipe/title "Cashew Vegan Carbonara"
             :recipe/ingredients "200g spaghetti\n150g smoked tofu\n100g soaked cashews\n200ml water\n3 tbsp nutritional yeast\n80g fresh peas\nblack pepper"
             :recipe/description "Blended cashews instead of oat cream — richer, silkier."})
        spicy (recipe/fork! conn alice cashew)
        _ (recipe/update! conn alice spicy
            {:recipe/title "Spicy Cashew Vegan Carbonara"
             :recipe/ingredients "200g spaghetti\n150g smoked tofu\n100g soaked cashews\n200ml water\n3 tbsp nutritional yeast\n80g fresh peas\n1 tsp chili flakes\nblack pepper"
             :recipe/description "The end of the line (so far). Chili flakes for heat."})
        ;; --- a side fork off the root, plus standalones ---
        bacon (recipe/fork! conn dave carbonara)
        _ (recipe/update! conn dave bacon
            {:recipe/title "Smoky Bacon Carbonara"
             :recipe/ingredients "200g spaghetti\n120g smoked bacon\n2 egg yolks\n1 whole egg\n50g parmesan\nblack pepper"
             :recipe/description "Guanciale is hard to find. Good smoked bacon isn't."})
        margherita (recipe/create! conn alice
                     {:title "Margherita Pizza"
                      :description "Three ingredients, done right."
                      :servings 4
                      :ingredients "500g 00 flour\n325g water\n10g salt\n3g fresh yeast\nSan Marzano tomatoes\nfior di latte\nbasil"
                      :steps "Mix and knead the dough\nProve 24h in the fridge\nShape and top with crushed tomatoes and torn mozzarella\nBake as hot as your oven goes\nFinish with fresh basil and olive oil"})
        _ (recipe/update! conn alice margherita
            {:recipe/steps "Mix and knead the dough until smooth\nProve 24h in the fridge, then 2h at room temperature\nShape by hand — never a rolling pin\nTop with crushed San Marzano and torn fior di latte\nBake as hot as your oven goes (250°C+)\nFinish with fresh basil and a drizzle of olive oil"})
        focaccia (recipe/create! conn bob
                   {:title "No-Knead Focaccia"
                    :description "Dimpled, oily, and forgiving."
                    :servings 8
                    :ingredients "500g bread flour\n400g water\n10g salt\n4g instant yeast\nolive oil\nflaky salt\nrosemary"
                    :steps "Mix into a shaggy dough\nFold a few times over 2 hours\nProve overnight in the fridge\nDimple into an oiled tray\nTop with oil, flaky salt and rosemary\nBake at 220°C until golden"})]
    (println "Seeded recipes:")
    (doseq [[label id] [["Classic Carbonara (alice, 3 versions)" carbonara]
                        ["Carbonara with Peas (bob)" peas]
                        ["Vegan Carbonara (carol)" vegan]
                        ["Cashew Vegan Carbonara (dave)" cashew]
                        ["Spicy Cashew Vegan Carbonara (alice) — descends from 4" spicy]
                        ["Smoky Bacon Carbonara (dave)" bacon]
                        ["Margherita Pizza (alice, 2 versions)" margherita]
                        ["No-Knead Focaccia (bob)" focaccia]]]
      (println (format "  %-50s /recipes/%s" label id)))
    {:users [alice bob carol dave]
     :recipes {:carbonara carbonara :spicy spicy :margherita margherita :focaccia focaccia}}))

(defn seed-if-empty!
  "Seed the dev database only when it has no recipes yet.

  Called from the dev startup (hot-reload/start), so a fresh dev DB comes up
  populated with the demo recipe graph, while an already-seeded DB is left
  untouched — `seed!` is additive, so re-seeding on every restart would pile up
  duplicate recipes. Returns true when it seeded."
  []
  (let [n (or (d/q '[:find (count ?e) . :where [?e :recipe/id]] (db/get-db)) 0)]
    (if (zero? (long n))
      (do (println "Empty dev database — seeding demo recipes...")
          (seed!)
          true)
      (do (println (format "Dev database has %d recipe(s) — skipping seed." (long n)))
          false))))
