// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.whalefall.movie;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Edition-bound metadata shared by the Android updater and offline behavior checks. */
class UpdateRelease {
 /** 同一 release 里可选下载的包体（mobile / arm64 / r8 / tv）；旧 feed 无此字段时为空。 */
 static final class Variant {
 final String kind, label, sha, url;
 final long size;
 final String[] downloads;

 Variant(String kind, String label, long size, String sha, String url, String[] downloads) {
 this.kind = kind;
 this.label = label;
 this.size = size;
 this.sha = sha;
 this.url = url;
 this.downloads = downloads;
 }
 }

 final int code, minSdk;
 final long size;
 final String version, url, sha, notes;
 final boolean preview;
 final String[] downloads;
 final Variant[] variants;

 UpdateRelease(JSONObject data, String channel, boolean mobile) throws Exception {
 if (data.getInt("schema") != 1) throw new IOException("Unsupported feed");
 boolean dedicated = data.has("platform");
 if (dedicated && !UpdatePolicy.platform(mobile).equals(data.getString("platform")))
 throw new IOException("更新信息与安装版本不匹配");
 // Legacy feeds contain TV metadata at the root. A phone MUST have its own
 // mobile artifact; absence is not permission to use the TV package.
 JSONObject artifact = !dedicated && mobile ? data.getJSONObject("mobile") : data;
 code =
 artifact.has("versionCode")
 ? artifact.getInt("versionCode")
 : data.getInt("versionCode");
 version =
 artifact.has("versionName")
 ? artifact.getString("versionName")
 : data.getString("versionName");
 minSdk = artifact.has("minSdk") ? artifact.getInt("minSdk") : data.getInt("minSdk");
 size = artifact.getLong("size");
 url = artifact.getString("url");
 sha = artifact.getString("sha256");
 preview =
 artifact.has("prerelease")
 ? artifact.getBoolean("prerelease")
 : data.getBoolean("prerelease");
 notes = artifact.optString("notes", data.optString("notes", ""));
 UpdatePolicy.validate(code, version, minSdk, size, sha, url, preview, channel, mobile);
 downloads =
 UpdatePolicy.downloads(version, mobile, url, artifact.optString("githubUrl", ""));
 if (notes.length() > 8000) throw new IOException("Notes too long");

 // 可选包体：向后兼容，旧 feed 没有 variants 时保持单包行为。
 java.util.List<Variant> list = new java.util.ArrayList<>();
 JSONArray array = artifact.optJSONArray("variants");
 if (array != null && array.length() > 0) {
 if (array.length() > 4) throw new IOException("Too many variants");
 java.util.Set<String> kinds = new java.util.HashSet<>();
 for (int i = 0; i < array.length(); i++) {
 JSONObject item = array.getJSONObject(i);
 String kind = item.optString("kind", "");
 // 电视只看 tv 包、手机不显示 tv 包：其它 edition 的种类直接忽略
 if (!UpdatePolicy.variantKindAllowed(kind, mobile)) continue;
 if (!kinds.add(kind)) throw new IOException("Duplicate variant");
 String label = item.optString("label", UpdatePolicy.variantLabel(kind, mobile));
 if (label.length() > 64) throw new IOException("Variant label too long");
 long variantSize = item.getLong("size");
 String variantSha = item.getString("sha256");
 String variantUrl = item.getString("url");
 UpdatePolicy.validateVariant(
 code, version, minSdk, variantSize, variantSha, variantUrl, channel, mobile,
 kind);
 list.add(
 new Variant(
 kind,
 label,
 variantSize,
 variantSha,
 variantUrl,
 UpdatePolicy.variantDownloads(
 version,
 mobile,
 variantUrl,
 item.optString("githubUrl", ""),
 kind)));
 }
 }
 variants = list.toArray(new Variant[0]);
 }

 /** 选中的变体；无 variants 时返回 null，沿用基础字段（旧 feed 行为）。 */
 Variant variant(int index) {
 if (variants.length == 0) return null;
 return variants[Math.max(0, Math.min(index, variants.length - 1))];
 }

 long targetSize(int index) {
 Variant v = variant(index);
 return v == null ? size : v.size;
 }

 String targetSha(int index) {
 Variant v = variant(index);
 return v == null ? sha : v.sha;
 }

 String[] targetDownloads(int index) {
 Variant v = variant(index);
 return v == null ? downloads : v.downloads;
 }

 String targetLabel(int index) {
 Variant v = variant(index);
 return v == null ? "默认包" : v.label;
 }

 String targetKind(int index) {
 Variant v = variant(index);
 return v == null ? "" : v.kind;
 }
}
