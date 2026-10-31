# Vita Flow

Open this folder in Android Studio (File > Open) and let Gradle sync (internet needed for the first sync).
Package app.vitaflow.com, minSdk 31, Kotlin + Jetpack Compose + Room + DataStore.

Inside: glassy dark/light UI, accounts (manual + Google), tasks with reminders, workouts with a run mode,
diet and water with a local food table, profile and progress, Max chat (rules first, Gemma only when needed),
Live voice mode, Tavily web search, model manager, undo, encrypted export/import, cloud backup disabled.

Models are added from Me > Settings > Model. Only .litertlm LLM files work (gemma-4-E2B-it.litertlm).
Before Google login works: fill GoogleConfig.WEB_CLIENT_ID in data/auth/GoogleAuth.kt.
If an older build was installed, uninstall it first (the database schema changed).

Natural speech: Me > Settings > "Max কীভাবে বুঝবে": Smart = the AI reads everything you say (several requests in one
sentence are fine); Quick = short simple commands use fixed rules first. Workout and diet plans are chosen by the AI
from the profile and the person's own words; limits, injuries, allergies and all calorie arithmetic are checked in code.

Chats and memory: several chats with history; inside a chat Max keeps a rolling summary + the latest messages and looks up older
ones when you mention them; outside a chat it knows a one-line digest of every chat plus lasting facts (Me > স্মৃতি, deletable).
Photos (gallery or camera), PDFs (first 2 pages) and text files can be sent; food photos can be logged as a meal after a yes.
