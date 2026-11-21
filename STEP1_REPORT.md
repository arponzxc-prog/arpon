# ধাপ ১ রিপোর্ট — AI এজেন্ট ইঞ্জিন / Step 1 Report

## কী করা হয়েছে (What was done)
- নেটিভ টুল-কলিং এজেন্ট লুপ: `data/ai/agent/AgentLoop.kt` (Gemini, OpenAI-compatible/Ollama, Anthropic — `AgentProtocols.kt`)
- ১১টি টুল + ২টি বিল্ড টুল: list_files, read_file(রেঞ্জ), search_in_project, get_project_info, create_project, write_file(শুধু নতুন), edit_file(একটাই মিল), delete_file, rename_file, get_diagnostics, ask_user, trigger_build, get_build_log
- সীমা: সর্বোচ্চ ৩০ ধাপ, একই কল ৩ বার হলে থামে, Stop বাটন (কোরুটিন ক্যানসেল + OkHttp call cancel), ব্যর্থ টুল কল → স্পষ্ট ERROR মডেলকে ফেরত
- finish_reason=length: টেক্সট হলে স্বয়ংক্রিয় continue (ইউজারকে নোটিস), কেটে যাওয়া টুল-আর্গুমেন্ট হলে এরর ফেরত → ছোট খণ্ডে রিট্রাই; কিছুই নীরবে ফেলা হয় না
- স্বয়ংক্রিয় যাচাই: লেখার পর ডায়াগনস্টিক, সর্বোচ্চ ৩ রাউন্ড মডেল নিজে ঠিক করে
- ফেইলওভার: `AgentModelClient` একই `AiFailoverPolicy`/cooldown ব্যবহার করে; শিফট হয় টুল চালানোর আগে, তাই কোনো ফাইল অপারেশন দ্বিতীয়বার চলে না (call-id cache সহ)
- কনটেক্সট: প্রজেক্ট ম্যাপ (ফাইল + এক-লাইন সারাংশ), NEXA.md মেমোরি, পুরোনো কথোপকথন ছাঁটা (টুল কল/রেজাল্ট জোড়া ভাঙে না)
- নিরাপত্তা: `ProjectGuard` (path traversal, .git, keystore/.env/local.properties ব্লক), API Key শুধু হেডারে (Gemini-র URL থেকে সরানো)
- চেকপয়েন্ট: `UndoJournal` (copy-on-write; প্রতি টাস্কে এক ট্যাপ Undo)
- UI (ন্যূনতম): AiAssistantScreen এজেন্ট লুপ ব্যবহার করে, Stop বাটন, "N ফাইল বদলেছে · Undo" ব্যানার

## বদলানো ফাইল
- যোগ: `data/ai/agent/{AgentModels,AgentToolSpecs,AgentProtocols,AgentLoop,AgentContext,AgentModelClient,AgentStatusText,NexaAgentRunner,ProjectGuard,ProjectToolExecutor,ProjectDiagnostics,UndoJournal}.kt`
- যোগ: `app/src/test/java/com/example/AgentStep1Test.kt` (৩২টি টেস্ট)
- বদল: `ui/screens/AiAssistantScreen.kt`
- অপরিবর্তিত: পুরোনো `GeminiAiService`, `NexaAiAgentEngine` (বিদ্যমান টেস্ট ও নন-এজেন্ট চ্যাটের জন্য রাখা)

## টেস্ট ফল
এই এনভায়রনমেন্টে Android SDK/Gradle/নেটওয়ার্ক নেই, তাই `./gradlew assembleDebug` ও `./gradlew test` **চালানো যায়নি**। কোড হাতে পরীক্ষা করা হয়েছে (ব্র্যাকেট ব্যালান্স, API মিল), কিন্তু কম্পাইল-যাচাই বাকি। প্রথমে এটা চালান:
`./gradlew assembleDebug test`

## বাকি ঝুঁকি / অনুমান
- কম্পাইল না-করা কোড: ছোটখাটো টাইপ/ইমপোর্ট এরর থাকতে পারে; এরর লগ পাঠালে ঠিক করে দেব।
- ask_user এখন সাধারণ টেক্সট প্রশ্ন (ট্যাপযোগ্য কার্ড ধাপ ২/৩-এ)।
- ডায়াগনস্টিক এখনও সরল (ব্র্যাকেট/কোটেশন/ট্যাগ/JSON); আসল HTML/JS parser ও বিল্ড লগ পার্সার ধাপ ৪-এ।
- Undo জার্নাল মেমরিতে (অ্যাপ বন্ধ হলে হারায়); create_project Undo করে না।
- Undo/এডিট হলে এডিটরের খোলা বাফার রিলোড ধাপ ৪-এ।
- trigger_build/get_build_log-এর হোস্ট ইমপ্লিমেন্টেশন (GitHub) এখনও জোড়া হয়নি — টুল লুকানো থাকে।
- Review-each-change মোড ও সেটিংস টগল ধাপ ২-এ।
- 429 ফেইলওভার লজিক বিদ্যমান পলিসি পুনঃব্যবহার করে; লাইভ 429 সিমুলেশন যাচাই হয়নি।
