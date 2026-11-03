package cz.anikoto.tv;

import android.net.Uri;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.json.JSONObject;
import java.util.*;

/** Reads public catalog pages; all interaction and playback stay in the TV activity. */
final class TvCatalog {
    static final String BASE="https://anikoto.cz";
    static class Anime {
        final String title,url,image;
        String detail="",description="";
        Anime(String title,String url,String image){this.title=title;this.url=url;this.image=image;}
    }
    static class Page {
        boolean home;
        ArrayList<Anime> titles=new ArrayList<>(),spotlight=new ArrayList<>();
        LinkedHashMap<String,ArrayList<Anime>> rows=new LinkedHashMap<>();
        LinkedHashMap<String,String> genres=new LinkedHashMap<>(),types=new LinkedHashMap<>();
        String next="",previous="";
    }
    static Document document(String url)throws Exception{return Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(25000).get();}
    static Document ajax(String path)throws Exception{
        String raw=Jsoup.connect(BASE+path).userAgent("Mozilla/5.0").header("X-Requested-With","XMLHttpRequest").referrer(BASE+"/home").ignoreContentType(true).timeout(25000).execute().body();
        JSONObject data=new JSONObject(raw);if(data.optInt("status")!=200)throw new IllegalStateException("Catalog unavailable");
        return Jsoup.parse(data.getString("result"),BASE);
    }
    static Page fetch(String url)throws Exception {
        Document doc=document(url);Page page=new Page();page.home="/home".equals(Uri.parse(url).getPath());
        for(Element link:doc.select("#menu a[href^=/genre/]"))page.genres.put(link.text(),link.absUrl("href"));
        for(Element link:doc.select("#menu a[href^=/type/]"))page.types.put(link.text(),link.absUrl("href"));
        if(page.home){
            page.spotlight=items(doc,"#hotest .swiper-slide");
            page.rows.put("Latest Episodes",items(doc,"#recent-update .tab-content[data-name=updated-all] .item"));
            if(page.rows.get("Latest Episodes").isEmpty())page.rows.put("Latest Episodes",items(doc,"#recent-update .ani.items .item"));
            page.rows.put("Upcoming Anime",items(doc,"#upcoming-anime .item"));
            page.rows.put("New Releases",items(doc,"section.top-table[data-name=new-release] .item"));
            page.rows.put("Newly Added",items(doc,"section.top-table[data-name=new-added] .item"));
            page.rows.put("Just Completed",items(doc,"section.top-table[data-name=completed] .item"));
            for(String period:new String[]{"day","week","month"})page.rows.put("Top Anime:"+period,items(doc,"#top-anime .tab-content[data-name="+period+"] .item"));
        }else{
            page.titles=items(doc,"#list-items .item");
            if(page.titles.isEmpty()&&!url.contains("keyword="))page.titles=items(doc,"aside.main .ani.items .item");
            Element next=doc.selectFirst("a[rel=next],.pagination .next a,.pagination li.next a"),prev=doc.selectFirst("a[rel=prev],.pagination .prev a,.pagination li.prev a");
            if(next!=null)page.next=next.absUrl("href");if(prev!=null)page.previous=prev.absUrl("href");
            int current=1;try{current=Integer.parseInt(Uri.parse(url).getQueryParameter("page"));}catch(Exception ignored){}
            if(page.next.isEmpty()&&page.titles.size()>=24)page.next=Uri.parse(url).buildUpon().clearQuery().encodedQuery(withPage(Uri.parse(url),current+1)).build().toString();
            if(page.previous.isEmpty()&&current>1)page.previous=Uri.parse(url).buildUpon().clearQuery().encodedQuery(withPage(Uri.parse(url),current-1)).build().toString();
        }
        return page;
    }
    private static String withPage(Uri uri,int page){Uri.Builder b=Uri.parse(BASE).buildUpon();for(String key:uri.getQueryParameterNames())if(!key.equals("page"))for(String value:uri.getQueryParameters(key))b.appendQueryParameter(key,value);b.appendQueryParameter("page",String.valueOf(page));return b.build().getEncodedQuery();}
    static ArrayList<Anime> items(Element container,String selector){
        LinkedHashMap<String,Anime> result=new LinkedHashMap<>();
        for(Element item:container.select(selector)){
            Element link=item.is("a[href*=/watch/]")?item:item.selectFirst("a[href*=/watch/]");Element image=item.selectFirst("img"),title=item.selectFirst(".d-title,h3,h2");
            if(link==null||image==null)continue;String url=link.absUrl("href");if(!"anikoto.cz".equals(Uri.parse(url).getHost()))continue;
            Anime anime=new Anime(title==null?image.attr("alt"):title.text(),url,image.hasAttr("data-src")?image.absUrl("data-src"):image.absUrl("src"));
            Element meta=item.selectFirst(".ak-detail,.meta"),desc=item.selectFirst(".ak-desc");
            ArrayList<String> bits=new ArrayList<>();
            if(meta!=null&&meta.hasClass("ak-detail")){for(Element part:meta.select(".it"))bits.add(part.text());}
            else{
                Element sub=item.selectFirst(".meta .ep-status.sub"),dub=item.selectFirst(".meta .ep-status.dub"),type=item.selectFirst(".meta .right");
                if(sub!=null)bits.add("SUB "+sub.text());if(dub!=null)bits.add("DUB "+dub.text());
                if(type!=null)bits.add(type.text());else for(Element part:item.select(".meta > .dot:not(.ep-wrap)"))bits.add(part.text());
            }
            anime.detail=bits.isEmpty()?(meta==null?"":meta.text()):android.text.TextUtils.join(" • ",bits);anime.description=desc==null?"":desc.text();result.putIfAbsent(url,anime);
        }
        return new ArrayList<>(result.values());
    }
}
