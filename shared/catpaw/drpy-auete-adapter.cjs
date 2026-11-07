// Copyright (C) 2026 Pilipala. SPDX-License-Identifier: GPL-3.0-only
'use strict';
// App-owned protocol adapter. Origins are extracted from a reviewed user import.
function serialize(value) {
  if (typeof value === 'function') return value.toString();
  if (Array.isArray(value)) return '[' + value.map(serialize).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.entries(value).map(([k, v]) => JSON.stringify(k) + ':' + serialize(v)).join(',') + '}';
  return JSON.stringify(value);
}
module.exports = function aueteRule(config) {
  if (!config || !/^https?:\/\//.test(config.origin) || !/^https?:\/\//.test(config.mediaBase)) throw Error('endpoint configuration required');
  const filters = {
    Movie: [['喜剧片','xjp'],['动作片','dzp'],['爱情片','aqp'],['科幻片','khp'],['恐怖片','kbp'],['惊悚片','jsp'],['战争片','zzp'],['剧情片','jqp']],
    Tv: [['美剧','oumei'],['韩剧','hanju'],['日剧','riju'],['泰剧','yataiju'],['网剧','wangju'],['台剧','taiju'],['国产','neidi'],['港剧','tvbgj']],
    Zy: [['国综','guozong'],['韩综','hanzong'],['美综','meizong']],
    Dm: [['动画','donghua'],['日漫','riman'],['国漫','guoman'],['美漫','meiman']],
    qita: [['纪录片','Jlp'],['经典片','Jdp'],['经典剧','Jdj'],['网大电影','wlp'],['国产老电影','laodianying']],
  };
  const rule = {
    title:'Auete', host:config.origin, homeUrl:'/',
    url:'/fyclass/fyfilterindexfypage.html[/fyclass/fyfilterindex.html]',
    filter_url:'{% if fl.type %}{{fl.type}}/{% endif %}',
    searchUrl:'/auete4so.php?searchword=**&page=fypage',
    searchable:2, quickSearch:1, filterable:1, limit:20, timeout:8000,
    headers:{'User-Agent':config.userAgent,'Accept-Language':'zh-CN,zh;q=0.8,en;q=0.2'},
    filter:Object.fromEntries(Object.entries(filters).map(([key, values]) => [key,
      [{key:'type',name:'分类',value:[{n:'全部',v:''},...values.map(([n,v])=>({n,v}))]}]])),
    预处理:'js:rule._init();',
    推荐:'js:VODS=rule._list(pdfa(rule._homeHtml,"ul.threadlist")[0]||"");',
    一级:'js:VODS=rule._list(request(rule._categoryUrl(input,MY_FL)));',
    搜索:'js:VODS=rule._list(request(rule._catalogUrl(input)));',
    二级:'js:VOD=rule._detail(input);',
    play_parse:true,lazy:'js:input=rule._play(input);',
    _address:function(value,base){
      if(typeof value!=='string'||!value||/[\x00-\x1f\x7f\\]/.test(value))unsupportedFeature('drpy_media_protocol');
      var normalized;
      try{normalized=joinUrl(base||cfg.origin,value);}catch(e){unsupportedFeature('drpy_media_protocol');}
      var match=/^(https?):\/\/([^/?#]+)([^?#]*)(?:[?#]|$)/i.exec(normalized);
      if(!match||match[2].indexOf('@')!==-1)unsupportedFeature('drpy_media_protocol');
      return {url:normalized,origin:match[1].toLowerCase()+'://'+match[2].toLowerCase(),pathname:match[3]||'/'};
    },
    _catalogUrl:function(value){
      var address=this._address(value);
      if(address.origin!==this._address(cfg.origin).origin)unsupportedFeature('drpy_source_origin');
      return address.url;
    },
    _categoryUrl:function(value,extend){
      var url=this._catalogUrl(value),address=this._address(url);
      var parts=/^\/(Movie|Tv|Zy|Dm|qita)\/(?:([^/]+)\/)?(index(?:[1-9]\d*)?\.html)$/.exec(address.pathname);
      if(!parts)unsupportedFeature('drpy_filter_not_supported');
      extend=extend||{};
      function active(value){return value!==undefined&&value!==null&&String(value).trim()!==''&&String(value).trim()!=='全部';}
      Object.keys(extend).forEach(function(key){if(key!=='type'&&key!=='class'&&active(extend[key]))unsupportedFeature('drpy_filter_not_supported');});
      var pathValue;try{pathValue=parts[2]?decodeURIComponent(parts[2]):'';}catch(e){unsupportedFeature('drpy_filter_not_supported');}
      var choices=(this.filter[parts[1]]||[]).filter(function(item){return item.key==='type';});
      var values=choices.length?choices[0].value:[],chosen='';
      [pathValue,extend.type,extend['class']].filter(active).forEach(function(selected){
        selected=String(selected).trim();
        var matches=values.filter(function(item){return item.v&&(selected===item.v||selected===item.n||selected===item.n.replace(/片$/,''));});
        if(matches.length!==1||chosen&&chosen!==matches[0].v)unsupportedFeature('drpy_filter_not_supported');
        chosen=matches[0].v;
      });
      return this._catalogUrl('/'+parts[1]+'/'+(chosen?chosen+'/':'')+parts[3]);
    },
    _init:function(){
      var html,seen={};
      try{html=request(cfg.origin);}catch(e){unsupportedFeature('drpy_source_unavailable');}
      this._homeHtml=html;
      var categories=pdfa(html,'ul.navbar-nav > li a').map(function(a){
        var match=/\/(Movie|Tv|Zy|Dm|qita)\/index\.html(?:[?#]|$)/.exec(pdfh(a,'a&&href'));
        if(!match||seen[match[1]])return null;seen[match[1]]=true;
        return {id:match[1],name:pdfh(a,'a&&Text')};
      }).filter(Boolean);
      if(!categories.length)unsupportedFeature('drpy_source_unavailable');
      this.class_url=categories.map(function(c){return c.id;}).join('&');
      this.class_name=categories.map(function(c){return c.name;}).join('&');
    },
    _list:function(html){
      if(pdfa(html,'input[name="validate"]').length&&pdfa(html,'img[src*="vdimgck"]').length)unsupportedFeature('drpy_verification_required');
      var self=this,seen={};
      return pdfa(html,'li').map(function(item){
        var href=pdfh(item,'h2 a&&href'),match=/\/(\w+\/\w+\/\w+)\/(?:[?#]|$)/.exec(href);
        if(!match||seen[match[1]])return null;seen[match[1]]=true;
        var name=pdfh(item,'h2 a&&title')||pdfh(item,'h2 a&&Text');
        if(!name)return null;
        return {vod_id:self._catalogUrl('/'+match[1]+'/'),vod_name:name,
          vod_pic:pd(item,'a.pic img&&src',cfg.origin),vod_remarks:pdfh(item,'span.hdtag&&Text')};
      }).filter(Boolean);
    },
    _detail:function(url){
      var html=request(this._catalogUrl(url)),self=this;
      var result={vod_id:url,vod_name:pdfh(html,'h1.detail-title&&Text').replace(/[《》]/g,'')||pdfh(html,'div.detail-main-card&&data-pname'),
        vod_pic:pd(html,'div.detail-poster img&&src',cfg.origin)||pd(html,'div.detail-main-card&&data-ppic',cfg.origin),
        vod_content:pdfh(html,'p.detail-des&&Text'),vod_play_from:'',vod_play_url:''};
      var labels={'类型':'type_name','导演':'vod_director','主演':'vod_actor','地区':'vod_area','年代':'vod_year','备注':'vod_remarks'};
      pdfa(html,'div.message p').forEach(function(p){
        var label=pdfh(p,'span.detail-label&&Text').replace(/[：:\s]/g,''),key=labels[label];
        if(key)result[key]=pdfh(p,'b&&Text')||pdfh(p,'p&&Text').replace(pdfh(p,'span.detail-label&&Text'),'').trim();
      });
      var lines=[],groups=[];
      pdfa(html,'div.play-card#player_list').forEach(function(card){
        var name=pdfh(card,'div.card-header span b&&Text').replace(/^.*[：:]\s*/,'');
        var episodes=pdfa(card,'ul.episode-list li > a').map(function(a){
          var href=pdfh(a,'a&&href');
          if(!/\/\w+\/\w+\/\w+\/play-\d+-\d+\.html(?:[?#]|$)/.test(href))return '';
          return pdfh(a,'a&&Text')+'$'+self._catalogUrl(href);
        }).filter(Boolean);
        if(name&&episodes.length){lines.push(name);groups.push(episodes.join('#'));}
      });
      result.vod_play_from=lines.join('$$$');result.vod_play_url=groups.join('$$$');return result;
    },
    _quoted:function(value){
      if(value[0]==='"')return JSON.parse(value);
      if(value[0]!=="'"||value[value.length-1]!=="'")throw Error('literal');
      return value.slice(1,-1).replace(/\\(u[\da-fA-F]{4}|[\\/'"])/g,function(_,escape){
        return escape[0]==='u'?String.fromCharCode(parseInt(escape.slice(1),16)):escape;
      });
    },
    _play:function(url){
      var html=request(this._catalogUrl(url)),scripts=pdfa(html,'div > script'),resolved='';
      for(var i=0;i<scripts.length&&!resolved;i++){
        var text=pdfh(scripts[i],'script&&Html');
        var pattern=/(?:^|[;\s])(?:var\s+)?now\s*=\s*(base64(?:decode)?\(\s*['"]?[A-Za-z\d+/_=-]+['"]?\s*\)|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\s*;/g,match;
        while((match=pattern.exec(text))&&!resolved){
          var value=match[1],decoded;
          if(/^base64(?:decode)?\(/.test(value)){
            var token=value.replace(/^base64(?:decode)?\(\s*['"]?/,'').replace(/['"]?\s*\)$/,'').replace(/-/g,'+').replace(/_/g,'/');
            if(token.length>8192||token.length%4===1)continue;
            while(token.length%4)token+='=';decoded=base64Decode(token);
          }else{try{decoded=this._quoted(value);}catch(e){continue;}}
          if(/^https?:\/\//i.test(decoded))resolved=this._address(decoded).url;
          else if(!/^[a-z][a-z\d+.-]*:/i.test(decoded)&&/\.m3u8(?:[?#]|$)/i.test(decoded))resolved=this._address(decoded,cfg.mediaBase).url;
          else unsupportedFeature('drpy_media_protocol');
        }
      }
      if(!resolved)unsupportedFeature('drpy_media_unresolved');
      return {parse:0,url:resolved,header:{'User-Agent':cfg.userAgent}};
    },
  };
  return 'var cfg='+JSON.stringify(config)+';\nvar rule='+serialize(rule)+';';
};
