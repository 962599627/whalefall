package com.whalefall.movie;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Dedicated global search page with a local remote-control keyboard. */
public final class GlobalSearchScreen implements AutoCloseable {
 final MainActivity a;
 final String initial;
 PinyinKeyboardModel keyboard;
 EditText input;
 TextView composition, status;
 boolean syncingInput, imeActive;
 long lastInputClickMs;
 Runnable pendingInputClick;
 LinearLayout candidates, keys, resultBody, sourceFilters;
 SearchCoordinator coordinator, retryCoordinator;
 List<String> searchVariants = Collections.emptyList();
 int sourceCount, columns = 4;
 private long searchStartMs;
 Button retrySource;
 boolean closed;
 int pageTicket, searchSerial, candidatePage, finished, failed, timedOut, busy, matched;
 String activeFilter = "", submitted = "", edited = "";
 Runnable pendingSearch, pendingRender;
 java.util.concurrent.Future<?> expansion;
 final Map<String, Button> filterButtons = new LinkedHashMap<>();
 final Set<String> displayed = new LinkedHashSet<>();
 LinearLayout resultGrid, lastRow;
 TextView emptyResult, resultHint;
 Button moreResults;
 int shownLimit = 24, renderPasses;
 boolean focusNewResults, restoringResults;
 ScrollView resultScroll;
 int savedResultScroll;
 long resultTime;
 Cms.Config resultConfig;
 final LinkedHashMap<String, SourceResult> results = new LinkedHashMap<>();
 final Map<String, LinearLayout> posterCards = new HashMap<>();
 final Map<PinyinKeyboardModel.Mode, Button> modeButtons = new LinkedHashMap<>();

 /** 1.9.27：跨源按标题归一化合并后的条目。 */
 static final class Merged {
 final String norm;
 String title;
 Cms.Video primaryVideo;
 Cms.Site primarySite;
 final List<Cms.Site> sites = new ArrayList<>();
 final List<Cms.Video> videos = new ArrayList<>();
 boolean playable;
 int sourceCount;
 String bestPoster;

 Merged(String norm) {
 this.norm = norm;
 }
 }

 int sortMode; // 0来源数 1可播优先 2相关度
 LinearLayout statusChips, sortRow;
 TextView mergeHint;
 private String lastAggregateMode = "";
 /** 1.9.29：多来源筛选（空 = 全部）。 */
 final LinkedHashSet<String> selectedSources = new LinkedHashSet<>();
 TextView filterSummaryText;
 Button filterClearChip;
 View filterRowView;
 final Map<String, TextView> groupHeaders = new HashMap<>();
 int realCardCount;

 boolean filtering() {
 return !selectedSources.isEmpty();
 }

 boolean sourceSelected(String key) {
 return selectedSources.isEmpty() || selectedSources.contains(key);
 }
 boolean mergedLogged;

 static String staticSubmitted = "";
 static Cms.Config staticConfig;
 static long staticTime;
 static int staticMatched;
 static final LinkedHashMap<String, SourceResult> staticResults = new LinkedHashMap<>();

 static final class SourceResult { final Cms.Site site;
 final List<Cms.Video> videos;
 final SearchCoordinator.Outcome outcome;

 SourceResult(Cms.Site site, List<Cms.Video> videos, SearchCoordinator.Outcome outcome) {
 this.site = site;
 this.videos = videos;
 this.outcome = outcome;
 }
 }

 public GlobalSearchScreen(MainActivity activity, String initialKeyword) {
 a = activity;
 initial = initialKeyword == null ? "" : initialKeyword;
 }

 public void show() {
 String restored = keyboard == null ? initial : keyboard.query();
 if (results.isEmpty()
 && !staticResults.isEmpty()
 && staticConfig == a.config
 && System.currentTimeMillis() - staticTime < 7200000L
 && (restored.trim().isEmpty() || restored.trim().equals(staticSubmitted))) {
 restored = staticSubmitted;
 submitted = staticSubmitted;
 resultConfig = staticConfig;
 resultTime = staticTime;
 matched = staticMatched;
 results.putAll(staticResults);
 }
 boolean searchAgain = !submitted.isEmpty() || !initial.trim().isEmpty();
 boolean reuse =
 !results.isEmpty()
 && submitted.equals(restored.trim())
 && resultConfig == a.config
 && System.currentTimeMillis() - resultTime < 7200000L;
 a.renderPage("", "");
 closed = false;
 edited = "";
 syncingInput = false;
 imeActive = false;
 lastInputClickMs = 0L;
 if (pendingInputClick != null) {
 a.ui.removeCallbacks(pendingInputClick);
 pendingInputClick = null;
 }
 filterButtons.clear();
 displayed.clear();
 posterCards.clear();
 modeButtons.clear();
 resultGrid = null;
 lastRow = null;
 emptyResult = null;
 moreResults = null;
 pageTicket = a.generation;
 a.root.setBackgroundColor(0x00000000);
 AppLog.i("Wallpaper", "page=GlobalSearchScreen bg=transparent");
 if (a.root.getChildAt(0) instanceof ImageLoader.Atmosphere) a.root.removeViewAt(0);
 a.body.setPadding(a.dp(32), a.dp(14), a.dp(32), a.dp(16));
 try {
 keyboard = new PinyinKeyboardModel(a.getAssets().open("search/pinyin-characters.tsv"));
 } catch (Exception missing) {
 a.paragraph("内置拼音字库无法读取，请重新安装完整版本。");
 return;
 }
 keyboard.set(restored);
 LinearLayout split = a.row();
 split.setGravity(Gravity.TOP);
 int height =
 Math.max(
 260,
 Math.round(a.windowHeight() / a.getResources().getDisplayMetrics().density)
 - 30);
 a.body.addView(split, new LinearLayout.LayoutParams(-1, a.dp(height)));
 LinearLayout left = a.vertical();
 left.setPadding(a.dp(4), a.dp(4), a.dp(24), a.dp(8));
 ScrollView keyboardScroll = new TvScrollView(a);
 keyboardScroll.setTag("search-keyboard-scroll");
 keyboardScroll.setVerticalScrollBarEnabled(false);
 keyboardScroll.setClipChildren(false);
 keyboardScroll.setClipToPadding(false);
 keyboardScroll.addView(left, new ScrollView.LayoutParams(-1, -2));
 split.addView(keyboardScroll, new LinearLayout.LayoutParams(a.dp(282), -1));
 columns =
 TvLayoutPolicy.searchColumns(
 a.windowWidth() / a.getResources().getDisplayMetrics().density - 346);
 TextView heading = a.text("搜索所有片源", 24, MainActivity.WHITE);
 heading.setTypeface(null, Typeface.BOLD);
 left.addView(heading);
 a.gap(left, 12);
 input =
 new EditText(a) {
 @Override
 public boolean onKeyPreIme(int keyCode, KeyEvent event) {
 if (keyCode == KeyEvent.KEYCODE_BACK
 && event.getAction() == KeyEvent.ACTION_UP
 && imeActive) {
 imeActive = false;
 setCursorVisible(false);
 setShowSoftInputOnFocus(false);
 }
 return super.onKeyPreIme(keyCode, event);
 }

 @Override
 public boolean onTouchEvent(MotionEvent event) {
 boolean hadFocus = hasFocus();
 boolean handled = super.onTouchEvent(event);
 if (event.getAction() == MotionEvent.ACTION_UP && !hadFocus && hasFocus()) {
 handleInputClick();
 }
 return handled;
 }

 @Override
 public boolean onKeyDown(int keyCode, KeyEvent event) {
 if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == KeyEvent.KEYCODE_ENTER
 || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
 if (event.getRepeatCount() == 0) {
 FocusMotion.key(a, this, keyCode, event);
 performClick();
 }
 return true;
 }
 if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
 || keyCode == KeyEvent.KEYCODE_DPAD_UP
 || (!imeActive
 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
 || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))) {
 return false;
 }
 return super.onKeyDown(keyCode, event);
 }

 @Override
 public boolean onKeyUp(int keyCode, KeyEvent event) {
 if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
 || keyCode == KeyEvent.KEYCODE_ENTER
 || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
 FocusMotion.key(a, this, keyCode, event);
 return true;
 }
 if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
 || keyCode == KeyEvent.KEYCODE_DPAD_UP
 || (!imeActive
 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
 || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT))) {
 return false;
 }
 return super.onKeyUp(keyCode, event);
 }
 };
 input.setTextSize(18);
 input.setTextColor(MainActivity.WHITE);
 input.setHintTextColor(a.themeColor(MainActivity.MUTED));
 input.setHint("输入片名…（双击调出输入法）");
 input.setGravity(Gravity.CENTER_VERTICAL);
 input.setIncludeFontPadding(false);
 input.setTag("primary");
 input.setContentDescription("搜索内容");
 input.setSingleLine(true);
 input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
 input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
 input.setShowSoftInputOnFocus(false);
 input.setCursorVisible(false);
 input.setEllipsize(android.text.TextUtils.TruncateAt.START);
 input.setPadding(a.dp(14), a.dp(10), a.dp(12), a.dp(10));
 input.setBackground(a.bg(0xff1a2b47, 10));
 input.setFocusable(true);
 input.setFocusableInTouchMode(true);
 input.setClickable(true);
 input.setOnFocusChangeListener(
 (v, f) -> {
 if (!f && imeActive) {
 imeActive = false;
 input.setCursorVisible(false);
 input.setShowSoftInputOnFocus(false);
 hideSystemIme();
 }
 FocusMotion.style(
 a,
 input,
 f,
 0xff1a2b47,
 0xff35353e,
 10,
 MainActivity.WHITE,
 MainActivity.WHITE,
 true,
 1.01f);
 });
 input.setOnClickListener(v -> handleInputClick());
 input.setOnEditorActionListener(
 (v, actionId, event) -> {
 hideSystemIme();
 submit();
 return true;
 });
 input.addTextChangedListener(
 new android.text.TextWatcher() {
 @Override
 public void beforeTextChanged(
 CharSequence s, int start, int count, int after) {}

 @Override
 public void onTextChanged(CharSequence s, int start, int before, int count) {}

 @Override
 public void afterTextChanged(android.text.Editable s) {
 if (syncingInput || keyboard == null) return;
 keyboard.set(s == null ? "" : s.toString());
 updateAfterImeInput();
 }
 });
 input.setOnKeyListener((v, key, event) -> physicalKey(key, event));
 left.addView(input, new LinearLayout.LayoutParams(-1, a.dp(48)));
 composition = a.text("", 12, MainActivity.MUTED);
 composition.setSingleLine(true);
 composition.setPadding(a.dp(2), a.dp(5), 0, 0);
 left.addView(composition, new LinearLayout.LayoutParams(-1, a.dp(25)));
 candidates = a.row();
 left.addView(candidates, new LinearLayout.LayoutParams(-1, a.dp(34)));
 LinearLayout modes = a.row();
 left.addView(modes, new LinearLayout.LayoutParams(-1, a.dp(38)));
 modeButton(modes, "拼音", PinyinKeyboardModel.Mode.PINYIN);
 modeButton(modes, "字母", PinyinKeyboardModel.Mode.LETTERS);
 modeButton(modes, "数字", PinyinKeyboardModel.Mode.NUMBERS);
 keys = a.vertical();
 left.addView(keys);
 a.gap(left, 5);
 LinearLayout editing = a.row();
 left.addView(editing);
 keyButton(
 editing,
 "空格",
 "search-space",
 () -> {
 keyboard.space();
 changed();
 },
 1);
 keyButton(
 editing,
 "删除",
 "search-delete",
 () -> {
 keyboard.backspace();
 changed();
 },
 1);
 keyButton(
 editing,
 "清空",
 "search-clear",
 () -> {
 keyboard.clear();
 changed();
 },
 1);
 Button submit = a.button("立即搜索", "search-submit", () -> submit());
 submit.setTextSize(15);
 submit.setMinHeight(a.dp(40));
 submit.setMinimumHeight(a.dp(40));
 submit.setPadding(a.dp(8), a.dp(5), a.dp(8), a.dp(5));
 LinearLayout.LayoutParams submitSize = new LinearLayout.LayoutParams(-1, a.dp(42));
 submitSize.topMargin = a.dp(8);
 left.addView(submit, submitSize);
 LinearLayout right = a.vertical();
 split.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
 // 1.9.31：来源筛选行固定在统计行之上（搜索框/历史 → 筛选行 → 统计 → 结果）
 HorizontalScrollView filterScroll = new HorizontalScrollView(a);
 filterScroll.setTag("search-source-scroll");
 filterScroll.setHorizontalScrollBarEnabled(false);
 filterScroll.setClipChildren(false);
 filterScroll.setClipToPadding(false);
 sourceFilters = a.row();
 sourceFilters.setClipChildren(false);
 sourceFilters.setClipToPadding(false);
 sourceFilters.setPadding(a.dp(4), a.dp(5), a.dp(4), a.dp(5));
 // 1.9.30：重建筛选行时清空旧按钮引用，避免 chips 消失/焦点丢失
 filterButtons.clear();
 filterClearChip = null;
 filterScroll.addView(sourceFilters);
 filterRowView = filterScroll;
 right.addView(filterScroll, new LinearLayout.LayoutParams(-1, a.dp(46)));
 status = a.text("输入即搜索 · 停顿片刻自动更新结果。", 14, MainActivity.MUTED);
 status.setMinHeight(a.dp(30));
 right.addView(status);
 filterSummaryText = a.text("", 12, UiTokens.ACCENT_SOFT);
 filterSummaryText.setPadding(a.dp(6), 0, a.dp(6), a.dp(2));
 filterSummaryText.setVisibility(View.GONE);
 filterSummaryText.setFocusable(true);
 filterSummaryText.setFocusableInTouchMode(true);
 filterSummaryText.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 v,
 focused,
 0x0016283f,
 0x33ff8fb1,
 999,
 0xffffb3c8,
 0xff0b1220,
 focused,
 1.02f));
 filterSummaryText.setOnClickListener(
 v -> {
 selectedSources.clear();
 activeFilter = "";
 shownLimit = 24;
 resetGrid();
 renderFilters();
 renderResults();
 });
 right.addView(filterSummaryText);
 resultScroll = new TvScrollView(a);
 resultScroll.setTag("global-search-results");
 resultScroll.setClipToPadding(false);
 resultBody = a.vertical();
 resultBody.setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(20));
 resultScroll.addView(resultBody);
 right.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));
 rebuildKeys();
 if (reuse) {
 edited = restored.trim();
 changed();
 resetGrid();
 restoringResults = true;
 renderFilters();
 renderResults();
 status.setText("已恢复上次结果 · " + matched + " 个来源有结果，可点立即搜索刷新");
 } else {
 changed();
 showHistory();
 if (searchAgain && !restored.trim().isEmpty()) submit();
 }
 }

 boolean current() {
 return !closed && !a.isFinishing() && pageTicket == a.generation;
 }

 void modeButton(LinearLayout row, String title, PinyinKeyboardModel.Mode mode) {
 Button b =
 keyButton(
 row,
 title,
 "search-mode:" + mode.name(),
 () -> {
 keyboard.mode(mode);
 candidatePage = 0;
 rebuildKeys();
 changed();
 },
 1);
 modeButtons.put(mode, b);
 }

 Button keyButton(LinearLayout row, String label, String tag, Runnable action, float weight) {
 Button b = a.button(label, tag, action);
 b.setMinWidth(0);
 b.setMinimumWidth(0);
 b.setMinHeight(0);
 b.setMinimumHeight(0);
 b.setTextSize(14);
 b.setPadding(a.dp(2), 0, a.dp(2), 0);
 b.setBackground(a.bg(0xff1a2b47, 7));
 b.setOnFocusChangeListener(
 (v, focus) -> {
 FocusMotion.style(
 a,
 b,
 focus,
 0xff1a2b47,
 MainActivity.WHITE,
 7,
 MainActivity.WHITE,
 MainActivity.DARK,
 false,
 1.02f);
 });
 b.setOnKeyListener((v, key, event) -> physicalKey(key, event));
 LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, a.dp(28), weight);
 size.setMargins(a.dp(2), a.dp(2), a.dp(2), a.dp(2));
 row.addView(b, size);
 return b;
 }

 void rebuildKeys() {
 keys.removeAllViews();
 String characters =
 keyboard.mode() == PinyinKeyboardModel.Mode.NUMBERS
 ? "1234567890-_:.,!?@#&/"
 : keyboard.mode() == PinyinKeyboardModel.Mode.LETTERS
 ? "abcdefghijklmnopqrstuvwxyz"
 : "abcdefghijklmnopqrstuvwxyz'";
 for (int start = 0; start < characters.length(); start += 6) {
 LinearLayout row = a.row();
 keys.addView(row);
 for (int column = 0; column < 6; column++) {
 int index = start + column;
 if (index >= characters.length()) {
 row.addView(new View(a), new LinearLayout.LayoutParams(0, a.dp(32), 1));
 continue;
 }
 String letter = characters.substring(index, index + 1);
 keyButton(
 row,
 letter.toUpperCase(Locale.ROOT),
 "search-key:" + letter,
 () -> {
 keyboard.input(letter);
 changed();
 },
 1);
 }
 }
 for (Map.Entry<PinyinKeyboardModel.Mode, Button> entry : modeButtons.entrySet())
 entry.getValue()
 .setText(
 (entry.getKey() == keyboard.mode() ? " " : "")
 + modeLabel(entry.getKey()));
 }

 String modeLabel(PinyinKeyboardModel.Mode mode) {
 return mode == PinyinKeyboardModel.Mode.PINYIN
 ? "拼音"
 : mode == PinyinKeyboardModel.Mode.LETTERS ? "字母" : "数字";
 }

 void handleInputClick() {
 long now = android.os.SystemClock.uptimeMillis();
 if (now - lastInputClickMs <= 500L) {
 lastInputClickMs = 0L;
 if (pendingInputClick != null) {
 a.ui.removeCallbacks(pendingInputClick);
 pendingInputClick = null;
 }
 openSystemIme();
 return;
 }
 lastInputClickMs = now;
 if (pendingInputClick != null) a.ui.removeCallbacks(pendingInputClick);
 pendingInputClick =
 () -> {
 pendingInputClick = null;
 if (!current() || imeActive) return;
 View first = a.root.findViewWithTag("search-key:a");
 if (first != null) first.requestFocus();
 };
 a.ui.postDelayed(pendingInputClick, 420);
 }

 void openSystemIme() {
 if (input == null || keyboard == null) return;
 imeActive = true;
 if (!keyboard.composition().isEmpty()) {
 keyboard.set(keyboard.query());
 }
 syncingInput = true;
 String currentText = keyboard.query();
 if (!currentText.equals(input.getText().toString())) {
 input.setText(currentText);
 }
 input.setSelection(input.getText().length());
 syncingInput = false;
 input.setShowSoftInputOnFocus(true);
 input.setCursorVisible(true);
 input.requestFocus();
 try {
 a.getWindow()
 .setSoftInputMode(
 WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
 | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
 } catch (Exception ignored) {
 }
 android.view.inputmethod.InputMethodManager imm =
 (android.view.inputmethod.InputMethodManager)
 a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
 if (imm != null) {
 if (!imm.showSoftInput(
 input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)) {
 imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
 }
 }
 if (composition != null) composition.setText("已调出电视输入法 · 可直接输入中文或拼音");
 }

 void hideSystemIme() {
 if (input == null) return;
 try {
 android.view.inputmethod.InputMethodManager imm =
 (android.view.inputmethod.InputMethodManager)
 a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
 if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
 } catch (Exception ignored) {
 }
 }

 boolean physicalKey(int key, KeyEvent event) {
 if (imeActive && a.getCurrentFocus() == input) {
 if (key == KeyEvent.KEYCODE_SEARCH && event.getAction() == KeyEvent.ACTION_DOWN) {
 hideSystemIme();
 submit();
 return true;
 }
 return false;
 }
 if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
 View focus = a.getCurrentFocus();
 if (focus != null) FocusMotion.key(a, focus, key, event);
 }
 if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
 if (key == KeyEvent.KEYCODE_DEL) {
 keyboard.backspace();
 changed();
 return true;
 }
 if (key == KeyEvent.KEYCODE_SPACE) {
 keyboard.space();
 changed();
 return true;
 }
 if (key == KeyEvent.KEYCODE_SEARCH) {
 submit();
 return true;
 }
 int unicode = event.getUnicodeChar();
 if (unicode >= 32
 && unicode < 127
 && key != KeyEvent.KEYCODE_DPAD_CENTER
 && key != KeyEvent.KEYCODE_ENTER) {
 keyboard.input(String.valueOf((char) unicode).toLowerCase(Locale.ROOT));
 changed();
 return true;
 }
 return false;
 }

 void changed() {
 if (input == null) return;
 String display =
 keyboard.query().isEmpty()
 ? ""
 : keyboard.committed()
 + (keyboard.composition().isEmpty()
 ? ""
 : " " + keyboard.composition());
 syncingInput = true;
 if (!display.equals(input.getText().toString())) {
 input.setText(display);
 input.setSelection(display.length());
 }
 syncingInput = false;
 updateAfterImeInput();
 }

 void updateAfterImeInput() {
 if (composition == null) return;
 composition.setText(
 keyboard.composition().isEmpty()
 ? (keyboard.mode() == PinyinKeyboardModel.Mode.PINYIN
 ? "默认识别首拼 / 全拼 · 双击输入框调出电视输入法"
 : "直接输入字母、首字母或数字 · 双击输入框调出输入法")
 : "拼音 " + keyboard.composition());
 candidatePage = 0;
 renderCandidates();
 scheduleSearch();
 }

 void renderCandidates() {
 candidates.removeAllViews();
 List<PinyinKeyboardModel.Candidate> values = keyboard.candidates();
 if (values.isEmpty()) {
 TextView hint =
 a.text(
 keyboard.composition().isEmpty() ? "" : "继续输入拼音，或切到字母搜索",
 12,
 MainActivity.MUTED);
 candidates.addView(hint);
 return;
 }
 int pages = (values.size() + 5) / 6;
 candidatePage = Math.max(0, Math.min(candidatePage, pages - 1));
 final int page = candidatePage;
 keyButton(
 candidates,
 "‹",
 "candidate-previous",
 () -> {
 candidatePage = Math.max(0, candidatePage - 1);
 renderCandidates();
 },
 1)
 .setEnabled(page > 0);
 for (int i = page * 6; i < Math.min(values.size(), page * 6 + 6); i++) {
 PinyinKeyboardModel.Candidate candidate = values.get(i);
 keyButton(
 candidates,
 candidate.text,
 "candidate:" + candidate.text,
 () -> {
 keyboard.select(candidate);
 changed();
 View first = a.root.findViewWithTag("search-key:a");
 if (first != null) first.requestFocus();
 },
 1);
 }
 keyButton(
 candidates,
 "›",
 "candidate-next",
 () -> {
 candidatePage = Math.min(pages - 1, candidatePage + 1);
 renderCandidates();
 },
 1)
 .setEnabled(page < pages - 1);
 }

 void scheduleSearch() {
 String query = keyboard.query().trim();
 if (query.equals(edited)) return;
 edited = query;
 if (pendingSearch != null) a.ui.removeCallbacks(pendingSearch);
 pendingSearch = null;
 ++searchSerial;
 restoringResults = false;
 cancelWork();
 if (query.isEmpty()) {
 submitted = "";
 results.clear();
 clearCards();
 filterButtons.clear();
 sourceFilters.removeAllViews();
 resultBody.removeAllViews();
 resultGrid = null;
 }

 if (query.isEmpty()) {
 status.setText("输入即搜索 · 支持中文、首拼和全拼");
 showHistory();
 return;
 }
 status.setText("正在输入… 停顿后自动搜索");
 pendingSearch =
 () -> {
 pendingSearch = null;
 if (current() && query.equals(keyboard.query().trim())) submit(false);
 };
 a.ui.postDelayed(pendingSearch, 650);
 }

 void submit() {
 submit(true);
 }

 void submit(boolean remember) {
 if (!current()) return;
 if (pendingSearch != null) {
 a.ui.removeCallbacks(pendingSearch);
 pendingSearch = null;
 }
 String query = keyboard.query().trim();
 if (query.isEmpty()) {
 status.setText("先输入一个片名。也可以搜索英文、首字母或数字。");
 return;
 }
 cancelWork();
 restoringResults = false;
 submitted = query;
 resultConfig = a.config;
 resultTime = System.currentTimeMillis();
 if (remember) saveHistory(query);
 if (coordinator != null) coordinator.close();
 results.clear();
 clearCards();
 finished = failed = timedOut = busy = matched = 0;
 activeFilter = "";
 selectedSources.clear();
 filterButtons.clear();
 sourceFilters.removeAllViews();
 shownLimit = 24;
 resetGrid();
 final int serial = ++searchSerial;
 List<Cms.Site> sources = new ArrayList<>();
 if (a.config != null)
 for (Cms.Site site : SourcePreferences.prioritized(a, a.config.sites))
 if (site.searchable && a.available(site)) sources.add(site);
 // Only changes scheduling order; every available searchable source participates.
 final String selected = a.prefs.getString(Prefs.SELECTED_SITE, "");
 Collections.sort(
 sources,
 (x, y) -> Boolean.compare(!x.key.equals(selected), !y.key.equals(selected)));
 final int total = sources.size();
 sourceCount = total;
 status.setText(total == 0 ? "没有可搜索来源，请先导入配置并启用来源扩展。" : "正在搜索全部 " + total + " 个来源…");
 if (total == 0) return;
 expansion =
 a.phoneticWork.submit(
 () -> {
 if (a.phonetic == null)
 try {
 a.phoneticReady.await(8, java.util.concurrent.TimeUnit.SECONDS);
 } catch (InterruptedException cancelled) {
 Thread.currentThread().interrupt();
 return;
 }
 if (Thread.currentThread().isInterrupted()) return;
 final PhoneticSearchIndex index = a.phonetic;
 if (index != null && !query.matches("[A-Za-z0-9' -]+"))
 if (!a.privateMode) index.prefer(query);
 final List<String> variants =
 index == null
 ? Collections.singletonList(query)
 : index.searchTerms(query);
 a.ui.post(
 () -> {
 if (current() && serial == searchSerial)
 startSources(sources, query, variants, serial);
 });
 });
 }

 void startSources(List<Cms.Site> sources, String query, List<String> variants, int serial) {
 final int total = sources.size();
 searchStartMs = System.currentTimeMillis();
 searchVariants = variants;
 if (variants.size() > 1) {
 String joined =
 variants.size() >= 2
 ? variants.get(0) + " / " + variants.get(1)
 : variants.get(0);
 status.setText("搜索全部 " + total + " 个来源 · 自动匹配 " + joined);
 }
 SearchCoordinator.configureThreads(a.prefs.getInt(Prefs.SEARCH_THREADS, 6));
 coordinator =
 new SearchCoordinator(
 (site, keyword) -> searchSource(site, query, variants),
 (site, videos, outcome) ->
 a.ui.post(
 () -> {
 if (!current() || serial != searchSerial) return;
 a.learnTitles(videos);
 SourceResult source =
 new SourceResult(site, videos, outcome);
 results.put(site.key, source);
 finished++;
 if (outcome == SearchCoordinator.Outcome.RESULT)
 matched++;
 if (outcome == SearchCoordinator.Outcome.FAILED)
 failed++;
 if (outcome == SearchCoordinator.Outcome.TIMEOUT)
 timedOut++;
 if (outcome == SearchCoordinator.Outcome.BUSY) busy++;
 staticSubmitted = submitted;
 staticConfig = resultConfig;
 staticTime = resultTime;
 staticMatched = matched;
 staticResults.clear();
 staticResults.putAll(results);
 updateStatus(total);
 if (finished >= total)
 AppLog.i(
 "SearchAgg",
 "sites="
 + total
 + " ok="
 + matched
 + " fail="
 + failed
 + " timeout="
 + timedOut
 + " ms="
 + (System.currentTimeMillis()
 - searchStartMs));
 scheduleRender();
 }),
 Prefs.clampSearchTimeoutSeconds(a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
 * 1000L,
 90000L);
 coordinator.start(sources, query);
 }

 List<Cms.Video> searchSource(Cms.Site site, String query, List<String> variants)
 throws Exception {
 return SearchVariants.run(
 query,
 variants,
 title ->
 !query.matches("[A-Za-z0-9' -]+")
 || a.phonetic == null
 || a.phonetic.matches(title, query),
 term -> {
 try {
 return Cms.listing(a.engine(site).search(term, 1)).videos;
 } catch (com.pilipala.catpaw.CatPawHost.DrpySourceException error) {
 if ("drpy_verification_required".equals(error.code))
 throw new SearchCoordinator.VerificationRequiredException();
 throw error;
 }
 });
 }

 void retrySelected() {
 SourceResult selected = results.get(activeFilter);
 if (selected == null || retryCoordinator != null) return;
 final int serial = searchSerial;
 retrySource.setEnabled(false);
 emptyResult.setText("正在重新搜索此源…");
 retryCoordinator =
 new SearchCoordinator(
 (site, query) -> searchSource(site, submitted, searchVariants),
 (site, videos, outcome) ->
 a.ui.post(
 () -> {
 if (!current() || serial != searchSerial) return;
 if (retryCoordinator != null) {
 retryCoordinator.close();
 retryCoordinator = null;
 }
 SourceResult old = results.get(site.key);
 if (old != null) {
 if (old.outcome == SearchCoordinator.Outcome.RESULT)
 matched--;
 if (old.outcome == SearchCoordinator.Outcome.FAILED)
 failed--;
 if (old.outcome
 == SearchCoordinator.Outcome.TIMEOUT)
 timedOut--;
 if (old.outcome == SearchCoordinator.Outcome.BUSY)
 busy--;
 }
 results.put(
 site.key,
 new SourceResult(site, videos, outcome));
 if (outcome == SearchCoordinator.Outcome.RESULT)
 matched++;
 if (outcome == SearchCoordinator.Outcome.FAILED)
 failed++;
 if (outcome == SearchCoordinator.Outcome.TIMEOUT)
 timedOut++;
 if (outcome == SearchCoordinator.Outcome.BUSY) busy++;
 updateStatus(sourceCount);
 // 1.9.29 P2：单源重试后重建分组标题/卡片计数
 resetGrid();
 renderResults();
 }),
 Prefs.clampSearchTimeoutSeconds(a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
 * 1000L,
 40000L);
 retryCoordinator.start(Collections.singletonList(selected.site), submitted);
 }

 void updateStatus(int total) {
 String text =
 (finished == total ? "搜索结束" : "搜索中")
 + " · "
 + finished
 + " / "
 + total
 + " 个来源已处理 · "
 + matched
 + " 个有结果";
 if (timedOut > 0) text += " · " + timedOut + " 个超时";
 if (failed > 0) text += " · " + failed + " 个暂不可用";
 if (busy > 0) text += " · " + busy + " 个未完成";
 int verification = 0;
 for (SourceResult result : results.values())
 if (result.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED) verification++;
 if (verification > 0) text += " · " + verification + " 个需网站验证码";
 status.setText(text);
 }

 void cancelWork() {
 if (retryCoordinator != null) {
 retryCoordinator.close();
 retryCoordinator = null;
 }
 if (expansion != null) {
 expansion.cancel(true);
 expansion = null;
 }
 if (coordinator != null) {
 coordinator.close();
 coordinator = null;
 }
 if (pendingRender != null) {
 a.ui.removeCallbacks(pendingRender);
 pendingRender = null;
 }
 }

 void clearCards() {
 for (LinearLayout card : posterCards.values())
 if (card.getChildCount() > 0) card.getChildAt(0).setTag(null);
 posterCards.clear();
 }

 void scheduleRender() {
 if (pendingRender != null) return;
 pendingRender =
 () -> {
 pendingRender = null;
 if (!current()) return;
 renderFilters();
 renderResults();
 };
 a.ui.postDelayed(pendingRender, 100);
 }

 void renderFilters() {
 // 1.9.30：来源筛选行固定显示：标签 + 全部 + 各源（数量/失败）
 if (sourceFilters.getChildCount() == 0 || !"source-filter-label".equals(
 String.valueOf(sourceFilters.getChildAt(0).getTag()))) {
 sourceFilters.removeAllViews();
 filterClearChip = null;
 TextView label = a.text("来源筛选：", 12, UiTokens.TEXT_TERTIARY);
 label.setTag("source-filter-label");
 label.setPadding(0, 0, a.dp(4), 0);
 label.setGravity(Gravity.CENTER_VERTICAL);
 sourceFilters.addView(label);
 }
 if (!filterButtons.containsKey("")) addFilter("全部", "");
 for (SourceResult source : results.values())
 if (!filterButtons.containsKey(source.site.key))
 addFilter(clean(source.site.name), source.site.key);
 updateFilterLabels();
 }

 void addFilter(String label, String key) {
 Button b =
 a.button(
 label,
 "search-filter:" + key,
 () -> toggleFilter(key));
 b.setTextSize(12);
 b.setMinWidth(a.dp(64));
 b.setMinimumWidth(a.dp(64));
 b.setMinHeight(0);
 b.setMinimumHeight(0);
 b.setPadding(a.dp(15), 0, a.dp(15), 0);
 b.setSingleLine(true);
 b.setGravity(Gravity.CENTER);
 b.setContentDescription(label);
 b.setOnFocusChangeListener((v, focus) -> styleFilter(b, key, focus));
 b.setOnLongClickListener(
 v -> {
 toggleFilter(key);
 return true;
 });
 LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, a.dp(34));
 size.setMargins(a.dp(2), 0, a.dp(8), 0);
 sourceFilters.addView(b, size);
 filterButtons.put(key, b);
 styleFilter(b, key, false);
 }

 /** 1.9.29：来源筛选（多选）；「全部」清空筛选。 */
 void toggleFilter(String key) {
 if (key.isEmpty()) {
 selectedSources.clear();
 } else if (!selectedSources.remove(key)) {
 selectedSources.add(key);
 }
 activeFilter = selectedSources.size() == 1 ? selectedSources.iterator().next() : "";
 shownLimit = 24;
 AppLog.i(
 "SearchFilter",
 "sources=" + selectedSources + " count=" + selectedSources.size());
 updateFilterLabels();
 resetGrid();
 renderResults();
 }

 String firstSelectedSource() {
 if (selectedSources.isEmpty()) return activeFilter;
 return selectedSources.iterator().next();
 }

 void styleFilter(Button b, String key, boolean focused) {
 boolean selected = key.isEmpty() ? selectedSources.isEmpty() : selectedSources.contains(key);
 b.setSelected(selected);
 b.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
 FocusMotion.style(
 a,
 b,
 focused,
 selected ? UiTokens.ACCENT : 0xff272f3a,
 selected ? 0xffffb3c8 : MainActivity.WHITE,
 17,
 selected ? 0xff0b1220 : 0xffc2cbd7,
 MainActivity.DARK,
 true,
 1.035f);
 }

 void updateFilterLabels() {
 for (Map.Entry<String, Button> entry : filterButtons.entrySet()) {
 String key = entry.getKey();
 Button b = entry.getValue();
 if (!key.isEmpty()) {
 SourceResult source = results.get(key);
 String label = clean(key.isEmpty() ? "" : (source == null ? key : source.site.name));
 if (source != null && source.videos != null) label += " " + source.videos.size();
 if (source != null) {
 if (source.outcome == SearchCoordinator.Outcome.FAILED
 || source.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED)
 label += " 失败";
 else if (source.outcome == SearchCoordinator.Outcome.TIMEOUT) label += " 超时";
 }
 b.setText(label);
 if (!key.isEmpty() && selectedSources.contains(key))
 b.setText("✓ " + b.getText());
 }
 styleFilter(b, key, b.hasFocus());
 if (!key.isEmpty()) {
 SourceResult source = results.get(key);
 if (source != null) {
 if (source.outcome == SearchCoordinator.Outcome.FAILED
 || source.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED) {
 b.setTextColor(0xff9aa3b2);
 } else if (source.outcome == SearchCoordinator.Outcome.TIMEOUT) {
 b.setTextColor(0xffffb454);
 }
 }
 }
 }
 if (filterClearChip == null) {
 filterClearChip = a.button("清除", "source-filter-clear", () -> toggleFilter(""));
 filterClearChip.setTextSize(12);
 filterClearChip.setMinHeight(0);
 filterClearChip.setMinimumHeight(0);
 filterClearChip.setPadding(a.dp(14), 0, a.dp(14), 0);
 filterClearChip.setGravity(Gravity.CENTER);
 filterClearChip.setTextColor(0xffff8fb1);
 FocusMotion.style(
 a,
 filterClearChip,
 false,
 0x003a1d2a,
 0x55ff8fb1,
 999,
 0xffff8fb1,
 0xff0b1220,
 false,
 1.02f);
 filterClearChip.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 filterClearChip,
 focused,
 0x003a1d2a,
 0x55ff8fb1,
 999,
 0xffff8fb1,
 0xff0b1220,
 focused,
 1.04f));
 LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-2, a.dp(34));
 clearLp.setMargins(a.dp(2), 0, a.dp(8), 0);
 sourceFilters.addView(filterClearChip, clearLp);
 }
 filterClearChip.setVisibility(filtering() ? View.VISIBLE : View.GONE);
 if (filterRowView != null && filterRowView.getParent() instanceof ViewGroup) {
 int rowIndex = ((ViewGroup) filterRowView.getParent()).indexOfChild(filterRowView);
 int statusIndex = -1;
 if (status != null && status.getParent() instanceof ViewGroup)
 statusIndex = ((ViewGroup) status.getParent()).indexOfChild(status);
 AppLog.i(
 "SearchFilter",
 "row=visible chips="
 + filterButtons.size()
 + " top="
 + (rowIndex >= 0 && rowIndex < statusIndex)
 + " rowIndex="
 + rowIndex
 + " statusIndex="
 + statusIndex);
 }
 if (filterSummaryText != null) {
 // 1.9.30：固定筛选行已有「清除」入口，摘要行仅保留隐藏状态，避免两个清除
 filterSummaryText.setVisibility(View.GONE);
 }
 }

 void resetGrid() {
 resultBody.removeAllViews();
 displayed.clear();
 groupHeaders.clear();
 realCardCount = 0;
 sortRow = null;
 statusChips = null;
 mergeHint = null;
 lastRow = null;
 resultGrid = a.vertical();
 resultGrid.setClipChildren(false);
 resultBody.addView(resultGrid);
 emptyResult = a.text("等待匹配结果…", 15, MainActivity.MUTED);
 emptyResult.setPadding(a.dp(8), a.dp(24), 0, 0);
 resultBody.addView(emptyResult);
 retrySource = a.navButton("重新搜索此源", "search-retry-source", this::retrySelected);
 retrySource.setVisibility(View.GONE);
 a.addButton(resultBody, retrySource);
 moreResults =
 a.navButton(
 "显示更多结果",
 "search-more-results",
 () -> {
 shownLimit = Math.min(120, shownLimit + 24);
 focusNewResults = true;
 renderResults();
 });
 moreResults.setVisibility(View.GONE);
 a.addButton(resultBody, moreResults);
 resultHint = a.text("全部视图显示前 120 条，可切换上方来源查看更多。", 12, MainActivity.MUTED);
 resultHint.setVisibility(View.GONE);
 resultBody.addView(resultHint);
 }

 /** 1.9.27：标题归一化（去空格/标点/清晰度/版本词；保留季/集/年份，避免不同季被合并）。 */
 static String normalizeTitle(String title) {
 if (title == null) return "";
 String text = title.toLowerCase(Locale.ROOT);
 text = text.replaceAll("[\\s\\p{Punct}，。！？、·：；“”‘’（）【】《》—…]+", "");
 text = text.replaceAll("[0-9]{3,4}p", "");
 text = text.replaceAll("(hd|bd|4k|8k|国语|粤语|中字|中文字幕|高清|完整版|未删减)", "");
 return text.trim();
 }

 List<Merged> buildMerged() {
 LinkedHashMap<String, Merged> map = new LinkedHashMap<>();
 int before = 0;
 for (SourceResult source : results.values()) {
 if (source.videos == null) continue;
 for (Cms.Video video : source.videos) {
 before++;
 String norm = normalizeTitle(video.title);
 if (norm.isEmpty()) norm = video.title == null ? "" : video.title;
 Merged merged = map.get(norm);
 if (merged == null) {
 merged = new Merged(norm);
 merged.title = video.title;
 merged.primaryVideo = video;
 merged.primarySite = source.site;
 merged.bestPoster = video.poster;
 // 1.9.27：primary 同时进入成对列表，保证 sites.size()==videos.size()
 merged.sites.add(source.site);
 merged.videos.add(video);
 merged.sourceCount = 1;
 if (video.lines != null && !video.lines.isEmpty()) merged.playable = true;
 map.put(norm, merged);
 continue;
 }
 if (merged.sites.contains(source.site)) continue; // 同源同标题保留首条
 merged.sites.add(source.site);
 merged.videos.add(video);
 merged.sourceCount = merged.sites.size();
 if ((merged.bestPoster == null || merged.bestPoster.isEmpty())
 && video.poster != null
 && !video.poster.isEmpty()) merged.bestPoster = video.poster;
 if (!merged.playable && video.lines != null && !video.lines.isEmpty())
 merged.playable = true;
 if (merged.sites.size() != merged.videos.size())
 AppLog.w(
 "SearchAgg",
 "pair mismatch sites="
 + merged.sites.size()
 + " videos="
 + merged.videos.size(),
 null);
 }
 }
 List<Merged> list = new ArrayList<>(map.values());
 Comparator<Merged> comparator =
 (x, y) -> {
 if (sortMode == 1 && x.playable != y.playable) return x.playable ? -1 : 1;
 if (sortMode == 2) {
 String q = normalizeTitle(submitted);
 boolean xm = !q.isEmpty() && x.norm.contains(q);
 boolean ym = !q.isEmpty() && y.norm.contains(q);
 if (xm != ym) return xm ? -1 : 1;
 }
 int byCount = Integer.compare(y.sourceCount, x.sourceCount);
 return byCount != 0 ? byCount : x.norm.compareTo(y.norm);
 };
 Collections.sort(list, comparator);
 AppLog.i(
 "SearchAgg",
 "sites="
 + sourceCount
 + " merged="
 + list.size()
 + " before="
 + before
 + " after="
 + list.size());
 return list;
 }

 /** 1.9.27：排序 chips + 失败源状态徽章。 */
 void ensureMergeControls() {
 if (sortRow == null) {
 sortRow = a.row();
 sortRow.setPadding(0, a.dp(2), 0, a.dp(4));
 String[] labels = {"来源数", "可播优先", "相关度"};
 for (int i = 0; i < labels.length; i++) {
 final int mode = i;
 Button b =
 a.button(
 labels[i],
 "search-sort:" + i,
 () -> {
 sortMode = mode;
 updateSortLabels();
 resetGrid();
 renderResults();
 });
 b.setTextSize(11);
 b.setMinWidth(0);
 b.setMinimumWidth(0);
 b.setMinHeight(0);
 b.setMinimumHeight(0);
 b.setPadding(a.dp(10), 0, a.dp(10), 0);
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, a.dp(30));
 lp.setMargins(a.dp(2), 0, a.dp(6), 0);
 sortRow.addView(b, lp);
 }
 resultBody.addView(sortRow, 0);
 }
 updateSortLabels();
 boolean aggregate = aggregateEnabled();
 if (mergeHint == null) {
 mergeHint =
 a.text(
 a.isTvDevice()
 ? "OK 进详情 · 长按 OK（或下方「切换来源」）选来源"
 : "长按卡片或点「切换来源」可切换来源",
 12,
 UiTokens.TEXT_TERTIARY);
 mergeHint.setPadding(0, 0, 0, a.dp(4));
 resultBody.addView(mergeHint, Math.min(1, resultBody.getChildCount()));
 }
 mergeHint.setVisibility(View.VISIBLE);
 mergeHint.setText(
 aggregate
 ? (a.isTvDevice()
 ? "OK 进详情 · 长按 OK（或下方「切换来源」）选来源"
 : "长按卡片或点「切换来源」可切换来源")
 : "聚合已关闭：按来源分组显示，可在设置开启「聚合搜索」");
 if (sortRow != null) sortRow.setVisibility(aggregate ? View.VISIBLE : View.GONE);
 if (statusChips == null) {
 statusChips = a.row();
 statusChips.setPadding(0, 0, 0, a.dp(6));
 resultBody.addView(statusChips, Math.min(1, resultBody.getChildCount()));
 }
 statusChips.removeAllViews();
 for (SourceResult source : results.values()) {
 boolean ok = source.outcome == SearchCoordinator.Outcome.RESULT;
 boolean fail =
 source.outcome == SearchCoordinator.Outcome.FAILED
 || source.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED;
 boolean timeout = source.outcome == SearchCoordinator.Outcome.TIMEOUT;
 if (!fail && !timeout && !ok) continue;
 int dotColor = ok ? 0xff2ad47a : timeout ? 0xffffb454 : 0xff9aa3b2;
 LinearLayout chip = a.row();
 chip.setGravity(Gravity.CENTER_VERTICAL);
 chip.setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4));
 chip.setBackground(MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 View dotView = new View(a);
 android.graphics.drawable.GradientDrawable dotDrawable =
 new android.graphics.drawable.GradientDrawable();
 dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
 dotDrawable.setColor(dotColor);
 dotView.setBackground(dotDrawable);
 LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(a.dp(6), a.dp(6));
 dotLp.rightMargin = a.dp(5);
 chip.addView(dotView, dotLp);
 TextView chipText =
 a.text(
 clean(source.site.name) + (fail ? " 失败" : timeout ? " 超时" : ""),
 10.5f,
 dotColor);
 chip.addView(chipText);
 chip.setMinimumHeight(a.dp(a.isTvDevice() ? 40 : 28));
 MobileDetailScreen.bindTouchFeedback(chip);
 chip.setFocusable(true);
 chip.setFocusableInTouchMode(true);
 chip.setOnFocusChangeListener(
 (v, focused) ->
 FocusMotion.style(
 a,
 v,
 focused,
 0x0016283f,
 0x33ffffff,
 999,
 0xffd7e0ee,
 0xff0b1220,
 focused,
 1.02f));
 final SourceResult selected = source;
 chip.setOnClickListener(
 v -> {
 String reason;
 switch (selected.outcome) {
 case FAILED:
 reason = "请求失败或返回无效响应";
 break;
 case TIMEOUT:
 reason = "请求超时（" + Prefs.clampSearchTimeoutSeconds(
 a.prefs.getInt(Prefs.SEARCH_TIMEOUT, 20))
 + " 秒）";
 break;
 case VERIFICATION_REQUIRED:
 reason = "需要网站验证码";
 break;
 case EMPTY:
 reason = "已响应但没有匹配结果";
 break;
 default:
 reason = "正常";
 break;
 }
 new AppDialog.Builder(a)
 .setTitle(clean(selected.site.name))
 .setMessage(reason)
 .setPositiveButton("关闭", (d, i) -> {})
 .show();
 });
 LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
 lp.setMargins(0, 0, a.dp(6), 0);
 statusChips.addView(chip, lp);
 }
 // 1.9.31：失败源信息已合并进固定筛选行，状态徽章行不再单独占位
 statusChips.setVisibility(View.GONE);
 // 1.9.29：resetGrid 重建 resultBody 后确保「前 120 条」提示仍在（且位于末尾）
 if (resultHint != null && resultHint.getParent() == null && resultBody != null) {
 resultBody.addView(resultHint);
 }
 }

 void updateSortLabels() {
 if (sortRow == null) return;
 for (int i = 0; i < sortRow.getChildCount(); i++) {
 View child = sortRow.getChildAt(i);
 if (!(child instanceof TextView)) continue;
 TextView view = (TextView) child;
 boolean active = i == sortMode;
 view.setTextColor(active ? 0xff0b1220 : MainActivity.WHITE);
 view.setBackground(
 active
 ? UiTokens.round(a, 999, UiTokens.ACCENT)
 : MobileDetailScreen.glassBg(a, 0x1affffff, 0x33ffffff, 999));
 }
 }

 void renderResults() {
 if (resultGrid == null) return;
 renderFilters();
 ensureMergeControls();
 String mode = aggregateEnabled() ? "on" : "off";
 if (!mode.equals(lastAggregateMode)) {
 lastAggregateMode = mode;
 AppLog.i("SearchAgg", "aggregate=" + mode);
 }
 renderPasses++;
 int available = 0, added = 0;
 boolean remaining = false;
 boolean aggregate = aggregateEnabled();
 if (!filtering() && aggregate) {
 // 1.9.27：全部视图用跨源合并去重后的列表
 List<Merged> mergedList = buildMerged();
 int[] state = {0, 0, 0};
 for (Merged merged : mergedList) {
 String key = "merged:" + merged.norm;
 LinearLayout card = posterCards.get(key);
 if (card == null) {
 card = makeMergedCard(merged);
 posterCards.put(key, card);
 }
 placeResult(key, card, state);
 if (state[2] == 1 || state[0] >= 120) break;
 }
 available = state[0];
 added = state[1];
 remaining = state[2] == 1;
 } else if (!filtering()) {
 // 1.9.29：聚合关闭 → 按来源分组逐源展示（标题只创建一次，避免 pending 重渲染叠加）
 int[] state = {0, 0, 0};
 for (SourceResult source : results.values()) {
 if (source.videos == null || source.videos.isEmpty()) continue;
 String groupKey = source.site.key;
 TextView header = groupHeaders.get(groupKey);
 if (header == null) {
 padCurrentRow();
 header =
 a.text(
 clean(source.site.name)
 + " · "
 + source.videos.size()
 + " 条",
 13,
 UiTokens.ACCENT_SOFT);
 header.setPadding(a.dp(4), a.dp(10), a.dp(4), a.dp(4));
 resultGrid.addView(header, new LinearLayout.LayoutParams(-1, -2));
 groupHeaders.put(groupKey, header);
 lastRow = null;
 }
 for (int i = 0; i < Math.min(24, source.videos.size()); i++) {
 Cms.Video video = source.videos.get(i);
 String key = source.site.key + ":" + video.id;
 LinearLayout card = posterCards.get(key);
 if (card == null) {
 card = makeCard(source, video);
 posterCards.put(key, card);
 }
 placeResult(key, card, state);
 if (state[2] == 1 || state[0] >= 120) break;
 }
 if (state[2] == 1 || state[0] >= 120) break;
 }
 available = state[0];
 added = state[1];
 remaining = state[2] == 1;
 } else {
 // 1.9.29：按来源筛选（可多选）→ 只显示选中来源的原始结果，不做合并
 int[] state = {0, 0, 0};
 for (SourceResult source : results.values()) {
 if (!sourceSelected(source.site.key)) continue;
 if (source.videos == null || source.videos.isEmpty()) continue;
 for (int i = 0; i < Math.min(24, source.videos.size()); i++) {
 Cms.Video video = source.videos.get(i);
 String key = source.site.key + ":" + video.id;
 LinearLayout card = posterCards.get(key);
 if (card == null) {
 card = makeCard(source, video);
 posterCards.put(key, card);
 }
 placeResult(key, card, state);
 if (state[2] == 1 || state[0] >= 120) break;
 }
 if (state[2] == 1 || state[0] >= 120) break;
 }
 available = state[0];
 added = state[1];
 remaining = state[2] == 1;
 }
 emptyResult.setVisibility(realCardCount == 0 ? View.VISIBLE : View.GONE);
 if (realCardCount == 0) {
 SourceResult selected = results.get(firstSelectedSource());
 emptyResult.setText(
 selected == null
 ? (finished == sourceCount ? "本次未获得可显示的结果。可选择上方来源查看具体状态。" : "正在等待其他来源…")
 : selected.outcome == SearchCoordinator.Outcome.VERIFICATION_REQUIRED
 ? "此片源搜索需要完成网站验证码，暂时无法返回结果。可先选择其他片源。"
 : selected.outcome == SearchCoordinator.Outcome.FAILED
 ? "此源请求失败或返回无效响应，并非确认没有内容。可重试或检查配置。"
 : selected.outcome == SearchCoordinator.Outcome.TIMEOUT
 ? "此源搜索超时，可单独重试。"
 : selected.outcome
 == SearchCoordinator.Outcome
 .BUSY
 ? "此源尚未完成，可单独重试。"
 : "此源已响应，但没有匹配结果。可更换关键词再试。");
 }
 retrySource.setVisibility(
 selectedSources.size() <= 1 && realCardCount == 0
 ? View.VISIBLE
 : View.GONE);
 retrySource.setEnabled(retryCoordinator == null);
 moreResults.setVisibility(available > shownLimit ? View.VISIBLE : View.GONE);
 resultHint.setVisibility(available >= 120 ? View.VISIBLE : View.GONE);
 if (!remaining && restoringResults) {
 restoringResults = false;
 View saved =
 a.current == null || a.current.focus == null
 ? null
 : a.root.findViewWithTag(a.current.focus);
 if (saved != null) saved.requestFocus();
 resultScroll.scrollTo(0, savedResultScroll);
 }
 if (remaining && pendingRender == null) {
 pendingRender =
 () -> {
 pendingRender = null;
 if (current()) renderResults();
 };
 a.ui.postDelayed(pendingRender, 16);
 }
 }

 boolean aggregateEnabled() {
 return a.prefs.getBoolean("search_aggregate_enabled", true);
 }

 /** 1.9.29：统一卡片落位（合并/按源分组/单源三模式共用）。state = {available, added, remaining}。 */
 void placeResult(String key, LinearLayout card, int[] state) {
 // 1.9.29 P1：先统计遍历到的候选（含已显示/跳过的），保证 pending 轮次和 120 上限正确
 state[0]++;
 if (state[0] > shownLimit) return;
 if (displayed.contains(key)) return;
 if (state[1] >= 4) {
 state[2] = 1;
 return;
 }
 if (lastRow == null || displayed.size() % columns == 0) newResultRow();
 if (card.getParent() instanceof ViewGroup)
 ((ViewGroup) card.getParent()).removeView(card);
 int column = displayed.size() % columns;
 lastRow.removeViewAt(column);
 LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
 cell.setMargins(a.dp(3), a.dp(3), a.dp(8), a.dp(13));
 lastRow.addView(card, column, cell);
 displayed.add(key);
 realCardCount++;
 state[1]++;
 if (focusNewResults) {
 card.requestFocus();
 focusNewResults = false;
 }
 }

 /** 为「按来源分组」模式补齐列占位，保证每组从新行开始。 */
 void padCurrentRow() {
 if (lastRow == null) return;
 while (displayed.size() % columns != 0) displayed.add("__group_pad:" + displayed.size());
 }

 void newResultRow() {
 lastRow = a.row();
 lastRow.setGravity(Gravity.TOP);
 lastRow.setClipChildren(false);
 resultGrid.addView(lastRow);
 for (int column = 0; column < columns; column++) {
 LinearLayout.LayoutParams blank = new LinearLayout.LayoutParams(0, 1, 1);
 blank.setMargins(a.dp(3), a.dp(3), a.dp(8), a.dp(13));
 lastRow.addView(new View(a), blank);
 }
 }

 /** 1.9.27：合并结果卡片（N 个来源 · 当前：来源名，长按选源）。 */
 LinearLayout makeMergedCard(final Merged merged) {
 Cms.Video video = merged.primaryVideo;
 SourceResult primary =
 new SourceResult(
 merged.primarySite, merged.videos, SearchCoordinator.Outcome.RESULT);
 LinearLayout card = makeCard(primary, video, merged.sourceCount);
 // P1：来源信息不截断，改为 2 行小字 + 可聚焦「N 个来源」按钮
 TextView count =
 a.text(
 merged.sourceCount
 + " 个来源 · 首选："
 + clean(merged.primarySite.name),
 10f,
 UiTokens.TEXT_TERTIARY);
 count.setMaxLines(2);
 count.setEllipsize(null);
 card.addView(count);
 final String[] names = new String[merged.sites.size()];
 for (int i = 0; i < names.length; i++) names[i] = clean(merged.sites.get(i).name);
 Button sources =
 a.button(
 "切换来源（" + merged.sourceCount + "）",
 "search-merged-sources:" + merged.norm,
 () -> showSourcePicker(merged, names));
 sources.setTextSize(11);
 sources.setMinHeight(0);
 sources.setMinimumHeight(0);
 sources.setPadding(0, 0, 0, 0);
 card.addView(
 sources, new LinearLayout.LayoutParams(-1, a.isTvDevice() ? a.dp(44) : a.dp(36)));
 card.setTag("global-merged:" + merged.norm);
 card.setOnLongClickListener(
 v -> {
 if (names.length == 0) return true;
 showSourcePicker(merged, names);
 return true;
 });
 return card;
 }

 void showSourcePicker(final Merged merged, String[] names) {
 if (names.length == 0) return;
 int current = merged.sites.indexOf(merged.primarySite);
 String[] labels = new String[names.length];
 for (int i = 0; i < names.length; i++)
 labels[i] = names[i] + (i == current ? " ✓ 当前" : "");
 new AppDialog.Builder(a)
 .setTitle(merged.title)
 .setItems(
 labels,
 (dialog, which) -> {
 Cms.Site site = merged.sites.get(which);
 Cms.Video target =
 which < merged.videos.size()
 ? merged.videos.get(which)
 : merged.primaryVideo;
 saveHistory(submitted);
 a.openListing(site, target);
 })
 .setNegativeButton("取消", null)
 .show();
 }

 LinearLayout makeCard(SourceResult source, Cms.Video video) {
 return makeCard(source, video, 0);
 }

 LinearLayout makeCard(SourceResult source, Cms.Video video, int sourceCount) {
 LinearLayout card = a.vertical();
 card.setPadding(a.dp(4), a.dp(4), a.dp(4), a.dp(8));
 FrameLayout posterWrap = new FrameLayout(a);
 ImageView poster = new ImageView(a);
 poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
 poster.setBackground(a.bg(0xff182a45, 9));
 poster.setClipToOutline(true);
 posterWrap.addView(poster, new FrameLayout.LayoutParams(-1, -1));
 if (sourceCount > 1) {
 TextView badge = a.text(sourceCount + " 源", 10.5f, MainActivity.WHITE);
 badge.setPadding(a.dp(6), a.dp(2), a.dp(6), a.dp(2));
 badge.setBackground(MobileDetailScreen.glassBg(a, 0x99000000, 0x40ffffff, 999));
 FrameLayout.LayoutParams badgeLp =
 new FrameLayout.LayoutParams(-2, a.dp(24), Gravity.TOP | Gravity.END);
 badgeLp.topMargin = a.dp(4);
 badgeLp.rightMargin = a.dp(4);
 posterWrap.addView(badge, badgeLp);
 }
 a.poster(video.poster, poster);
 LinearLayout.LayoutParams posterSize = new LinearLayout.LayoutParams(a.dp(110), a.dp(165));
 posterSize.gravity = Gravity.CENTER_HORIZONTAL;
 card.addView(posterWrap, posterSize);
 TextView title = a.text(video.title, 14, MainActivity.WHITE);
 title.setSingleLine(true);
 title.setEllipsize(android.text.TextUtils.TruncateAt.END);
 title.setPadding(a.dp(2), a.dp(6), 0, 0);
 card.addView(title);
 TextView origin = a.text(clean(source.site.name), 11, MainActivity.MUTED);
 origin.setSingleLine(true);
 origin.setEllipsize(android.text.TextUtils.TruncateAt.END);
 card.addView(origin);
 card.setTag("global-result:" + source.site.key + ":" + video.id);
 card.setFocusable(true);
 card.setFocusableInTouchMode(true);
 card.setClickable(true);
 a.pressFeedback(card);
 final String query = submitted;
 card.setOnClickListener(
 view -> {
 saveHistory(query);
 a.openListing(source.site, video);
 });
 card.setOnFocusChangeListener(
 (view, selected) -> {
 FocusMotion.style(
 a,
 card,
 selected,
 Color.TRANSPARENT,
 0xff393940,
 11,
 MainActivity.WHITE,
 MainActivity.WHITE,
 true,
 1.035f);
 });
 return card;
 }

 String focusedTag(ViewGroup group) {
 View focus = a.getCurrentFocus();
 if (focus == null || focus.getTag() == null) return null;
 View parent = focus;
 while (parent != null) {
 if (parent == group) return String.valueOf(focus.getTag());
 if (!(parent.getParent() instanceof View)) break;
 parent = (View) parent.getParent();
 }
 return null;
 }

 String clean(String title) {
 return title == null
 ? "来源"
 : title.replaceAll("[\\p{So}\\p{Cs}]", "").replace('┃', ' ').trim();
 }

 void showHistory() {
 resultBody.removeAllViews();
 resultBody.addView(a.text("最近搜索", 17, MainActivity.WHITE));
 List<String> history = history();
 if (history.isEmpty()) {
 TextView hint =
 a.text(
 "支持中文、首拼和全拼，例如 st / santi。\n也可以输入字母、数字或选择拼音候选字。",
 15,
 MainActivity.MUTED);
 hint.setLineSpacing(a.dp(6), 1);
 hint.setPadding(0, a.dp(18), 0, 0);
 resultBody.addView(hint);
 return;
 }
 for (String value : history) {
 Button recent =
 a.navButton(
 value,
 "search-history:" + value,
 () -> {
 keyboard.set(value);
 changed();
 submit();
 });
 a.addButton(resultBody, recent);
 }
 Button clear =
 a.navButton(
 "清除搜索记录",
 "search-clear-history",
 () -> {
 a.prefs.edit().remove(Prefs.GLOBAL_SEARCH_HISTORY).apply();
 showHistory();
 });
 a.addButton(resultBody, clear);
 }

 List<String> history() {
 List<String> out = new ArrayList<>();
 try {
 JSONArray list = new JSONArray(a.prefs.getString(Prefs.GLOBAL_SEARCH_HISTORY, "[]"));
 for (int i = 0; i < Math.min(8, list.length()); i++) {
 String text = list.optString(i);
 if (!text.isEmpty()) out.add(text);
 }
 } catch (Exception ignored) {
 }
 return out;
 }

 void saveHistory(String query) {
 if (a.privateMode) return;
 List<String> previous = history();
 previous.remove(query);
 previous.add(0, query);
 JSONArray list = new JSONArray();
 for (int i = 0; i < Math.min(8, previous.size()); i++) list.put(previous.get(i));
 a.prefs.edit().putString(Prefs.GLOBAL_SEARCH_HISTORY, list.toString()).apply();
 }

 @Override
 public void close() {
 closed = true;
 if (resultScroll != null) savedResultScroll = resultScroll.getScrollY();
 if (pendingInputClick != null) {
 a.ui.removeCallbacks(pendingInputClick);
 pendingInputClick = null;
 }
 hideSystemIme();
 if (pendingSearch != null) {
 a.ui.removeCallbacks(pendingSearch);
 pendingSearch = null;
 }
 clearCards();
 searchSerial++;
 cancelWork();
 }
}
