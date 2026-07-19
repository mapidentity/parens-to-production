(ns myapp.i18n.nl
  "Dutch UI strings, keyed by namespaced keyword (e.g. :home/sign-in).
  Kept deliberately flat and data-only so it hot-reloads cleanly.")

(def translations
  "Dutch translations."
  {;; Meta
   :meta/description
   "Een recepten-versiesite — fork recepten, pas ze aan en zie precies wat er veranderde. Git, maar dan voor koken."

   ;; Landing page
   :home/get-started "Aan de slag"
   :home/email-label "E-mailadres"
   :home/email-placeholder "jij@voorbeeld.nl"
   :home/sign-in "Inloggen"
   :home/magic-link-explanation
   "We mailen je een magic link om in te loggen. Geen wachtwoord nodig."
   :home/lead
   "Fork elk recept, maak het van jou en bewaar elke versie. Zie regel voor regel het verschil tussen 'oma's' en die van jou."
   :home/tagline-1 "Git, maar dan voor koken."
   :home/tagline-2 "Fork het recept. Behoud de afstamming."
   :home/tagline-3 "Elke aanpassing, in versies."
   :home/tagline-4 "Diff je diner."
   :home/tagline-5 "Deze carbonara stamt af van vier voorouders."
   :home/tagline-6 "Vertak, proef, voeg samen, herhaal."

   ;; Auth
   :auth/check-email "Check je e-mail"
   :auth/email-sent-to "We hebben een magic link gestuurd naar "
   :auth/link-expires
   "Klik op de link in de e-mail om in te loggen. De link verloopt over 15 minuten."
   :auth/safe-to-close "Je kunt dit tabblad sluiten — de link opent in een nieuw venster."
   :auth/back-to-home "← Terug naar home"
   :auth/sign-out "Uitloggen"

   ;; Email
   :email/magic-link-subject "Inloggen bij MyApp"
   :email/magic-link-body
   "Klik op deze link om in te loggen bij MyApp:\n\n%s\n\nWerkt de link niet? Kopieer en plak hem in je browser.\n\nDeze link verloopt over 15 minuten.\n\nAls je dit niet hebt aangevraagd, kun je deze e-mail veilig negeren."

   ;; Terms / onboarding
   :terms/welcome-title "Welkom bij MyApp"
   :terms/welcome-explanation "Voor je begint met koken, even de huisregels."
   :terms/terms-summary
   "Dit is een demo-applicatie uit het boek \"Building a Clojure/Datomic SaaS from Scratch.\" Recepten die je maakt zijn openbaar en mogen door iedereen geforkt worden. Wees aardig, plaats niets wat je niet gedeeld wilt zien, en veel plezier."
   :terms/accept-button "Akkoord, ik ga koken"
   :terms/decline-button "Nee bedankt, log me uit"

   ;; Navigation
   :nav/browse "Recepten"
   :nav/new "Nieuw recept"
   :nav/dashboard "Dashboard"
   :nav/admin "Beheer"

   ;; Recipes
   :recipe/browse-title "Recepten"
   :search/title "Zoek recepten"
   :search/label "Zoeken"
   :search/placeholder "Zoek recepten…"
   :search/button "Zoeken"
   :search/no-results "Geen recepten gevonden voor je zoekopdracht."
   :recipe/new "Nieuw recept"
   :recipe/edit "Bewerken"
   :recipe/delete "Verwijderen"
   :recipe/delete-confirm "Dit recept verwijderen? Dit kan niet ongedaan worden gemaakt."
   :recipe/actions "Acties"
   :common/cancel "Annuleren"
   :recipe/fork "Fork"
   :recipe/fork-this "Fork dit recept"
   :recipe/login-to-fork "Log in om te forken"
   :recipe/history "Versiegeschiedenis"
   :recipe/by "door"
   :recipe/servings "Porties"
   :recipe/ingredients "Ingrediënten"
   :recipe/steps "Bereiding"
   :recipe/forked-from "Geforkt van"
   :recipe/original "Origineel recept"
   :recipe/no-recipes "Nog geen recepten."
   :recipe/note-label "Wat is er veranderd? (optioneel)"
   :recipe/note-placeholder "bijv. Zout gehalveerd — het was te veel"
   :recipe/preview-title "Voorvertoning"
   :recipe/preview-waiting
   "De voorvertoning verschijnt zodra het recept een titel en porties heeft."
   :recipe/title-label "Titel"
   :recipe/description-label "Omschrijving (Markdown ondersteund)"
   :recipe/servings-label "Porties"
   :recipe/ingredients-label "Ingrediënten (één per regel)"
   :recipe/steps-label "Bereiding (één stap per regel)"
   :recipe/save "Recept opslaan"
   :recipe/conflict-title "Iemand anders heeft dit recept bewerkt terwijl jij het openstond."
   :recipe/conflict-body
   "Je wijzigingen hieronder blijven behouden. Nogmaals opslaan overschrijft de huidige versie —"
   :recipe/conflict-review "bekijk wat er is veranderd"
   :recipe/create "Recept aanmaken"
   :recipe/forks "Forks"
   :recipe/no-forks "Nog geen forks — wees de eerste."
   :recipe/updated "Bijgewerkt"
   :recipe/created "Aangemaakt"
   :recipe/version "Versie"
   :recipe/view-this-version "Bekijk deze versie"
   :recipe/diff-from-previous "Wijzigingen t.o.v. vorige"
   :recipe/current "huidig"
   :recipe/initial "eerste versie"
   :recipe/viewing-historical "Je bekijkt een eerdere versie van dit recept."
   :recipe/back-to-recipe "← Terug naar huidige versie"
   :recipe/changes-title "Wijzigingen"
   :recipe/no-changes "Geen verschillen tussen deze versies."
   :recipe/legend-added "toegevoegd"
   :recipe/legend-removed "verwijderd"

   ;; Dashboard
   :dashboard/title "Dashboard"
   :dashboard/welcome "Welkom terug"
   :activity/title "Terwijl je weg was"
   :activity/forked-yours "heeft je recept geforkt:"
   :activity/updated-upstream "heeft het origineel van je fork bijgewerkt:"
   :dashboard/your-recipes "Jouw recepten"
   :dashboard/no-recipes "Je hebt nog geen recepten aangemaakt."
   :dashboard/create-cta "Maak je eerste recept"
   :dashboard/reorder-hint "Sleep aan de greep, of gebruik ▲▼, om te herordenen."
   :dashboard/drag-handle "Sleep om te herordenen"
   :dashboard/move-up "Omhoog"
   :dashboard/move-down "Omlaag"

   ;; Errors
   :error/title "Fout"
   :error/invalid-magic-link "Ongeldige of verlopen magic link. Vraag een nieuwe aan."
   :error/not-found "Niet gevonden."
   :error/server-error "Er ging iets mis aan onze kant. Probeer het opnieuw."
   ;; Formuliervalidatie — één melding per (veld, code) uit recipe/conform.
   :error/note-too-long "Houd de notitie korter dan 500 tekens."
   :error/title-blank "Geef het recept een titel."
   :error/title-too-long "Houd de titel korter dan 200 tekens."
   :error/servings-not-a-number "Porties moet een geheel getal zijn."
   :error/servings-out-of-range "Porties moet tussen 1 en 100 liggen."
   :error/description-too-long "Deze beschrijving is te lang."
   :error/ingredients-too-long "De ingrediëntenlijst is te lang."
   :error/steps-too-long "De bereidingswijze is te lang."})
