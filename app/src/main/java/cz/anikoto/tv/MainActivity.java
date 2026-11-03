package cz.anikoto.tv;

import android.app.*;
import android.os.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.util.*;
import java.util.concurrent.*;
import cz.anikoto.tv.TvCatalog.Anime;

/** Native TV catalog and playback controls around the website's hosted player. */
public class MainActivity extends Activity {
    private static final int BG=0xFF0B0D12, PANEL=0xFF20232D, WHITE=0xFFF5F5F7, RED=0xFFE93B46;
    private final ExecutorService network=Executors.newFixedThreadPool(4);
    private final ExecutorService catalogNetwork=Executors.newFixedThreadPool(2);
    private Future<?> catalogRequest;
    private TvCatalog.Page currentPage;
    private String browseTitle="Anime library";
    private boolean scheduleScreen=false;
    private long currentScheduleDay=0;
    private int scheduleGeneration=0;
    private EditText searchInput;
    private boolean searchMode=false;
    private final Runnable liveSearch=()->loadCatalog(searchInput.getText().toString().trim().isEmpty()?"https://anikoto.cz/home":"https://anikoto.cz/filter?keyword="+Uri.encode(searchInput.getText().toString().trim()));
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final android.util.LruCache<String,Bitmap> images=new android.util.LruCache<String,Bitmap>(24*1024*1024){
        protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };
    private FrameLayout root,player;
    private LinearLayout home,rows,selectionPane,serverChoices;
    private FrameLayout controls;
    private SeekBar seekBar;
    private TextView elapsedTime,totalTime;
    private long lastSeek=0;
    private JSONArray captionTracks=new JSONArray();
    private int selectedCaption=0;
    private Button subtitleButton;
    private Button skipIntroButton,skipOutroButton;
    private TextView selectionTitle,selectionHint;
    private Button episodeButton;
    private TextView heroTitle,message,progress;
    private WebView web;
    private View customView,lastCard;
    private WebChromeClient.CustomViewCallback customCallback;
    private Button playButton;
    private Button speedButton;
    private float playbackSpeed=1f;
    private boolean speedDialogOpen=false;
    private boolean playingScreen=false,videoAvailable=false,homeLoading=false;
    private long lastVideo=0;
    private int loadGeneration=0;
    private String catalogUrl="https://anikoto.cz/home", currentTitle="", currentUrl="";
    private boolean awaitingChoice=false;
    private String serverSignature="",expectedEpisode="";
    private JSONArray episodes=new JSONArray(),servers=new JSONArray();
    private final Runnable inspect=new Runnable(){public void run(){if(web!=null&&playingScreen)inspectPlayer();handler.postDelayed(this,1500);}};
    private final Runnable hideDock=()->{if(playingScreen&&!awaitingChoice&&videoAvailable&&!speedDialogOpen)hideControls();};
    private void scheduleHide(){handler.removeCallbacks(hideDock);if(playingScreen&&!awaitingChoice&&controls.getVisibility()==View.VISIBLE)handler.postDelayed(hideDock,5000);}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        playbackSpeed=getPreferences(0).getFloat("playbackSpeed",1f);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        createHome();createPlayer();handler.post(inspect);loadCatalog(catalogUrl);
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private GradientDrawable shape(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));if(stroke!=0)d.setStroke(dp(3),stroke);return d;}
    private void focusStyle(View view){
        StateListDrawable bg=new StateListDrawable();bg.addState(new int[]{android.R.attr.state_focused},shape(PANEL,WHITE));bg.addState(new int[]{},shape(PANEL,0));view.setBackground(bg);
        view.setFocusable(true);view.setFocusableInTouchMode(true);
    }
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(16);b.setTextColor(WHITE);b.setAllCaps(false);b.setPadding(dp(14),0,dp(14),0);focusStyle(b);
        b.setOnClickListener(v->action.run());return b;
    }
    private void addButton(LinearLayout line,String label,Runnable action){Button b=button(label,action);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(48));lp.setMargins(dp(5),dp(4),dp(5),dp(4));line.addView(b,lp);}
    private void createHome(){
        home=new LinearLayout(this);home.setOrientation(1);home.setPadding(dp(32),dp(18),dp(24),0);root.addView(home,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);home.addView(nav,new LinearLayout.LayoutParams(-1,dp(60)));
        TextView brand=text("ANIKOTO",25,RED);brand.setTypeface(null,1);nav.addView(brand,new LinearLayout.LayoutParams(dp(190),-2));
        addButton(nav,"Home",()->{leaveSearch();loadCatalog("https://anikoto.cz/home");});
        addButton(nav,"Search",this::search);
        addButton(nav,"Browse",this::browseMenu);
        addButton(nav,"Schedule",()->openSchedule(0));
        addButton(nav,"Refresh",()->{if(scheduleScreen)openSchedule(currentScheduleDay);else loadCatalog(catalogUrl);});
        searchInput=new EditText(this);searchInput.setSingleLine(true);searchInput.setTextSize(22);searchInput.setTextColor(WHITE);searchInput.setHintTextColor(0xFFADB0BF);searchInput.setHint("Search anime…");searchInput.setPadding(dp(18),0,dp(18),0);focusStyle(searchInput);searchInput.setId(View.generateViewId());
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        LinearLayout.LayoutParams searchLayout=new LinearLayout.LayoutParams(-1,dp(54));searchLayout.setMargins(dp(4),dp(12),dp(4),dp(16));home.addView(searchInput,searchLayout);searchInput.setVisibility(View.GONE);
        searchInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(!searchMode)return;handler.removeCallbacks(liveSearch);++loadGeneration;if(catalogRequest!=null)catalogRequest.cancel(true);message.setText(s.toString().trim().isEmpty()?"Loading anime…":"Searching…");handler.postDelayed(liveSearch,450);}
            public void afterTextChanged(android.text.Editable s){}
        });
        searchInput.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH||action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){hideKeyboard();if(!homeLoading&&lastCard!=null&&lastCard.isAttachedToWindow())lastCard.requestFocus();return true;}return false;});
        searchInput.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN){hideKeyboard();if(lastCard!=null&&lastCard.isAttachedToWindow())lastCard.requestFocus();}return true;}return false;});
        heroTitle=text("Anime, on your big screen.",34,WHITE);heroTitle.setTypeface(null,1);heroTitle.setMaxLines(2);
        LinearLayout.LayoutParams titleLayout=new LinearLayout.LayoutParams(-1,dp(100));titleLayout.topMargin=dp(24);home.addView(heroTitle,titleLayout);
        heroTitle.setVisibility(View.GONE);
        message=text("Loading anime…",18,0xFFADB0BF);home.addView(message);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);home.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        rows=new LinearLayout(this);rows.setOrientation(1);scroll.addView(rows);
    }
    private void loadCatalog(String url){
        scheduleScreen=false;++scheduleGeneration;catalogUrl=url;homeLoading=true;int generation=++loadGeneration;message.setVisibility(View.VISIBLE);message.setText("Loading anime…");rows.removeAllViews();lastCard=null;
        if(catalogRequest!=null)catalogRequest.cancel(true);
        catalogRequest=catalogNetwork.submit(()->{
            try{
                TvCatalog.Page page=TvCatalog.fetch(url);
                runOnUiThread(()->{if(generation!=loadGeneration||isFinishing())return;homeLoading=false;currentPage=page;renderPage(page);});
            }catch(Exception e){runOnUiThread(()->{if(generation!=loadGeneration)return;homeLoading=false;message.setText("Couldn't load anime. Check the network and choose Refresh.");});}
        });
    }
    private void renderPage(TvCatalog.Page page){
        rows.removeAllViews();lastCard=null;
        if(!page.home){renderCatalog(page.titles);LinearLayout pagination=new LinearLayout(this);if(!page.previous.isEmpty())addButton(pagination,"Previous page",()->loadCatalog(page.previous));if(!page.next.isEmpty())addButton(pagination,"Next page",()->loadCatalog(page.next));rows.addView(pagination);return;}
        message.setText("");message.setVisibility(View.GONE);
        if(!page.spotlight.isEmpty())addSpotlight(page.spotlight);
        ArrayList<Anime> latest=page.rows.get("Latest Episodes");if(latest!=null)addLatestRow(latest);
        addTopAnime(page);
        String[] names={"Upcoming Anime","New Releases","Newly Added","Just Completed"};String[] paths={"/status/not-yet-aired","/new-release","/status/currently-airing","/status/finished-airing"};
        for(int i=0;i<names.length;i++){final String name=names[i],path=paths[i];ArrayList<Anime> list=page.rows.get(name);if(list!=null&&!list.isEmpty()){LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView label=text(name,22,WHITE);label.setTypeface(null,1);header.addView(label,new LinearLayout.LayoutParams(0,-2,1));addButton(header,"View all",()->navigate(name,TvCatalog.BASE+path));rows.addView(header);addCardStrip(list);}}
        LinearLayout scheduleHeader=new LinearLayout(this);TextView label=text("Estimated Schedule",22,WHITE);scheduleHeader.addView(label,new LinearLayout.LayoutParams(0,-2,1));addButton(scheduleHeader,"Open weekly schedule",()->openSchedule(0));rows.addView(scheduleHeader);
        LinearLayout scheduleList=new LinearLayout(this);scheduleList.setOrientation(1);rows.addView(scheduleList);loadScheduleList(scheduleList,0,false);
        if(!searchMode&&lastCard!=null)lastCard.requestFocus();
    }
    private void renderCatalog(ArrayList<Anime> list){
        message.setVisibility(list.isEmpty()?View.VISIBLE:View.GONE);message.setText(list.isEmpty()?"No titles found. Try another search.":"");
        rows.removeAllViews();
        int split=Math.min(9,list.size());
        if(split>0)addRow(catalogUrl.contains("keyword=")?"Search results":browseTitle,list.subList(0,split));
        if(list.size()>split)addRow("More to discover",list.subList(split,list.size()));
        if(!searchMode&&lastCard!=null)lastCard.requestFocus();
    }
    private void addRow(String title,List<Anime> anime){
        TextView label=text(title,22,WHITE);label.setTypeface(null,1);rows.addView(label);
        addCardStrip(anime);
    }
    private LinearLayout addCardStrip(List<Anime> anime){
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setClipToPadding(false);scroll.setHorizontalScrollBarEnabled(false);scroll.setPadding(dp(6),dp(12),dp(6),dp(22));
        rows.addView(scroll,new LinearLayout.LayoutParams(-1,dp(295)));
        LinearLayout line=new LinearLayout(this);scroll.addView(line);
        populateCards(line,anime);
        return line;
    }
    private void populateCards(LinearLayout line,List<Anime> anime){
        line.removeAllViews();
        for(Anime a:anime){
            LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(4),dp(4),dp(4),dp(4));focusStyle(card);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(150),dp(260));lp.rightMargin=dp(18);line.addView(card,lp);
            ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackgroundColor(PANEL);card.addView(poster,new LinearLayout.LayoutParams(-1,dp(180)));loadImage(a.image,poster);
            TextView name=text(a.title,14,WHITE);name.setMaxLines(2);name.setPadding(dp(7),dp(6),dp(4),0);card.addView(name,new LinearLayout.LayoutParams(-1,dp(48)));
            TextView detail=text(a.detail,12,0xFFADB0BF);detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);detail.setPadding(dp(7),0,dp(4),0);card.addView(detail,new LinearLayout.LayoutParams(-1,dp(22)));
            card.setOnFocusChangeListener((v,focus)->{if(focus){heroTitle.setText(a.title);lastCard=card;}});
            card.setOnClickListener(v->openAnime(a));if(lastCard==null||!lastCard.isAttachedToWindow())lastCard=card;
            if(searchMode)card.setNextFocusUpId(searchInput.getId());
        }
    }
    private void navigate(String title,String url){leaveSearch();browseTitle=title;loadCatalog(url);}
    private void browseMenu(){
        String[] labels={"Genres","Types","Updated","Added","Popular","Upcoming","Ongoing","Completed","Schedule"};
        String[] paths={"","","/latest-updated","/new-release","/most-viewed","/status/not-yet-aired","/status/currently-airing","/status/finished-airing",""};
        new AlertDialog.Builder(this).setTitle("Browse anime").setItems(labels,(d,index)->{
            if(index<2){if(currentPage==null){Toast.makeText(this,"Catalog is still loading",Toast.LENGTH_SHORT).show();return;}chooseCategory(index==0?"Genres":"Types",index==0?currentPage.genres:currentPage.types);}
            else if(index==8)openSchedule(0);else navigate(labels[index],TvCatalog.BASE+paths[index]);
        }).setNegativeButton("Close",null).show();
    }
    private void chooseCategory(String title,LinkedHashMap<String,String> links){
        String[] labels=links.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(title).setItems(labels,(d,index)->navigate(labels[index],links.get(labels[index]))).setNegativeButton("Back",(d,w)->browseMenu()).show();
    }
    private void addSpotlight(List<Anime> list){
        final int[] index={0};FrameLayout banner=new FrameLayout(this);banner.setPadding(dp(4),dp(4),dp(4),dp(4));focusStyle(banner);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,dp(225));layout.setMargins(dp(5),dp(12),dp(5),dp(25));rows.addView(banner,layout);
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);banner.addView(image,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xFF0B0D12,0xEE0B0D12,0x400B0D12}));banner.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(1);copy.setPadding(dp(25),dp(18),dp(16),dp(12));banner.addView(copy,new FrameLayout.LayoutParams(dp(570),-1));
        TextView counter=text("",14,RED);copy.addView(counter);
        TextView title=text("",29,WHITE);title.setTypeface(null,1);title.setMaxLines(2);copy.addView(title,new LinearLayout.LayoutParams(-1,dp(78)));
        TextView meta=text("",15,0xFFADB0BF);meta.setMaxLines(1);copy.addView(meta);
        TextView description=text("",16,0xFFD0D2DB);description.setMaxLines(2);description.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(description);
        TextView hint=text("◀   Spotlight   ▶     •     OK to open",13,0xFFADB0BF);FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT);hp.setMargins(0,0,dp(20),dp(15));banner.addView(hint,hp);
        Runnable update=()->{Anime a=list.get(index[0]);counter.setText("SPOTLIGHT  "+(index[0]+1)+" / "+list.size());title.setText(a.title);meta.setText(a.detail);description.setText(a.description);loadImage(a.image,image);};update.run();
        banner.setOnClickListener(v->openAnime(list.get(index[0])));
        banner.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.getAction()==KeyEvent.ACTION_DOWN){index[0]=(index[0]+list.size()+(key==KeyEvent.KEYCODE_DPAD_LEFT?-1:1))%list.size();update.run();}return true;}return false;});
        banner.setOnFocusChangeListener((v,focus)->{if(focus)lastCard=banner;});lastCard=banner;
    }
    private void addLatestRow(List<Anime> initial){
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Latest Episodes",22,WHITE);title.setTypeface(null,1);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));rows.addView(header);
        LinearLayout cards=addCardStrip(initial);final int[] request={0};
        String[] names={"All","Sub","Dub","Trending","Random"},keys={"updated-all","updated-sub","updated-dub","trending","random"};
        ArrayList<Button> tabs=new ArrayList<>();
        for(int i=0;i<names.length;i++){final int tab=i;Button b=compactButton(names[i],()->{
            for(int j=0;j<tabs.size();j++)tabs.get(j).setTextColor(j==tab?RED:WHITE);
            int version=++request[0],generation=loadGeneration;
            catalogNetwork.execute(()->{try{Document doc=TvCatalog.ajax("/ajax/home/widget/"+keys[tab]+"?page=1");ArrayList<Anime> list=TvCatalog.items(doc,".item");runOnUiThread(()->{if(generation==loadGeneration&&version==request[0]&&cards.isAttachedToWindow())populateCards(cards,list);});}catch(Exception e){runOnUiThread(()->{if(generation==loadGeneration)Toast.makeText(this,"Could not load this episode filter",Toast.LENGTH_SHORT).show();});}});
        });tabs.add(b);if(i==0)b.setTextColor(RED);addCompact(header,b,dp(i==3?90:65));}
        addButton(header,"View all",()->navigate("Updated",TvCatalog.BASE+"/latest-updated"));
    }
    private void addTopAnime(TvCatalog.Page page){
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Top Anime",22,WHITE);title.setTypeface(null,1);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));rows.addView(header);
        ArrayList<Anime> day=page.rows.get("Top Anime:day");LinearLayout cards=addCardStrip(day==null?Collections.emptyList():ranked(day));
        ArrayList<Button> tabs=new ArrayList<>();String[] periods={"Day","Week","Month"};
        for(int i=0;i<periods.length;i++){final int tab=i;Button button=compactButton(periods[i],()->{for(int j=0;j<tabs.size();j++)tabs.get(j).setTextColor(j==tab?RED:WHITE);ArrayList<Anime> list=page.rows.get("Top Anime:"+periods[tab].toLowerCase(Locale.US));if(list!=null)populateCards(cards,ranked(list));});tabs.add(button);if(i==0)button.setTextColor(RED);addCompact(header,button,dp(90));}
    }
    private List<Anime> ranked(List<Anime> source){ArrayList<Anime> result=new ArrayList<>();for(int i=0;i<source.size();i++){Anime a=source.get(i),copy=new Anime(a.title,a.url,a.image);copy.detail="#"+(i+1)+"  •  "+a.detail;result.add(copy);}return result;}
    private void openSchedule(long day){
        leaveSearch();scheduleScreen=true;++loadGeneration;++scheduleGeneration;if(catalogRequest!=null)catalogRequest.cancel(true);homeLoading=false;rows.removeAllViews();lastCard=null;message.setVisibility(View.GONE);
        Calendar date=Calendar.getInstance();if(day>0)date.setTimeInMillis(day*1000);date.set(Calendar.HOUR_OF_DAY,0);date.set(Calendar.MINUTE,0);date.set(Calendar.SECOND,0);date.set(Calendar.MILLISECOND,0);long selected=date.getTimeInMillis()/1000;
        currentScheduleDay=selected;
        TextView title=text("Estimated Schedule  •  "+TimeZone.getDefault().getID(),24,WHITE);title.setTypeface(null,1);title.setPadding(0,dp(16),0,dp(18));rows.addView(title);
        HorizontalScrollView dayScroll=new HorizontalScrollView(this);dayScroll.setHorizontalScrollBarEnabled(false);rows.addView(dayScroll);LinearLayout days=new LinearLayout(this);dayScroll.addView(days);
        addButton(days,"‹ Week",()->openSchedule(selected-7*86400));
        Calendar monday=(Calendar)date.clone();monday.add(Calendar.DAY_OF_MONTH,-((monday.get(Calendar.DAY_OF_WEEK)+5)%7));
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE dd MMM",Locale.US);
        for(int i=0;i<7;i++){long stamp=monday.getTimeInMillis()/1000;Button button=compactButton(format.format(monday.getTime()),()->openSchedule(stamp));if(stamp==selected)button.setTextColor(RED);addCompact(days,button,dp(110));monday.add(Calendar.DAY_OF_MONTH,1);}
        addButton(days,"Week ›",()->openSchedule(selected+7*86400));
        LinearLayout list=new LinearLayout(this);list.setOrientation(1);list.setPadding(0,dp(20),0,dp(20));rows.addView(list);loadScheduleList(list,selected,true);
    }
    private void loadScheduleList(LinearLayout target,long day,boolean standalone){
        int generation=scheduleGeneration;target.addView(text("Loading schedule…",18,0xFFADB0BF));
        double offset=TimeZone.getDefault().getOffset(System.currentTimeMillis())/3600000.0;
        catalogNetwork.execute(()->{try{
            Document doc=TvCatalog.ajax(day==0?"/ajax/schedule?tz="+offset:"/ajax/schedule/date?tz="+offset+"&time="+day);
            ArrayList<String[]> entries=new ArrayList<>();for(Element item:doc.select("a.item[href*=/watch/]")){Element title=item.selectFirst(".d-title"),time=item.selectFirst(".time"),episode=item.selectFirst(".ep");if(title!=null&&time!=null&&"anikoto.cz".equals(Uri.parse(item.absUrl("href")).getHost()))entries.add(new String[]{title.text(),item.absUrl("href"),time.text(),episode==null?"":episode.text()});}
            runOnUiThread(()->{if(generation!=scheduleGeneration||!target.isAttachedToWindow())return;target.removeAllViews();if(entries.isEmpty())target.addView(text("No releases listed for this date.",18,0xFFADB0BF));
                for(String[] entry:entries){LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(16),dp(10),dp(16),dp(10));focusStyle(line);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);target.addView(line,lp);
                    TextView time=text(entry[2],20,RED);line.addView(time,new LinearLayout.LayoutParams(dp(80),-2));TextView name=text(entry[0],18,WHITE);name.setMaxLines(2);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView ep=text(entry[3],16,0xFFADB0BF);line.addView(ep,new LinearLayout.LayoutParams(dp(120),-2));
                    line.setOnClickListener(v->openAnime(new Anime(entry[0],entry[1],"")));line.setOnFocusChangeListener((v,focused)->{if(focused)lastCard=line;});if(standalone&&lastCard==null)lastCard=line;
                }
            });
        }catch(Exception e){runOnUiThread(()->{if(generation==scheduleGeneration&&target.isAttachedToWindow()){target.removeAllViews();target.addView(text("Schedule unavailable. Open Schedule to retry.",18,0xFFADB0BF));}});}});
    }
    private void loadImage(String url,ImageView target){
        target.setTag(url);
        Bitmap cached=images.get(url);if(cached!=null){target.setImageBitmap(cached);return;}
        network.execute(()->{try{
            java.net.HttpURLConnection conn=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();conn.setConnectTimeout(12000);conn.setReadTimeout(12000);
            try(java.io.InputStream in=conn.getInputStream()){BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=2;Bitmap image=BitmapFactory.decodeStream(in,null,opt);if(image!=null){images.put(url,image);runOnUiThread(()->{if(!isDestroyed()&&url.equals(target.getTag()))target.setImageBitmap(image);});}}finally{conn.disconnect();}
        }catch(Exception ignored){}});
    }
    private void search(){
        searchMode=true;searchInput.setVisibility(View.VISIBLE);heroTitle.setVisibility(View.GONE);searchInput.requestFocus();
        searchInput.post(()->((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
    }
    private void hideKeyboard(){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(searchInput.getWindowToken(),0);}
    private void leaveSearch(){handler.removeCallbacks(liveSearch);searchMode=false;hideKeyboard();searchInput.setVisibility(View.GONE);heroTitle.setVisibility(View.GONE);searchInput.setText("");}
    private String asset(String name){try(java.io.InputStream in=getAssets().open(name)){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}catch(Exception e){throw new IllegalStateException(e);}}
    private void createPlayer(){
        player=new FrameLayout(this);player.setBackgroundColor(Color.BLACK);root.addView(player,new FrameLayout.LayoutParams(-1,-1));player.setVisibility(View.GONE);
        web=new WebView(this);player.addView(web,new FrameLayout.LayoutParams(-1,-1));
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        boolean scripts=WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)&&WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if(scripts){
            WebViewCompat.addWebMessageListener(web,"tvPlayback",Collections.singleton("*"),(view,msg,origin,main,reply)->{
                if(!playingScreen)return;
                try{JSONObject data=new JSONObject(msg.getData());boolean firstVideo=!videoAvailable;lastVideo=SystemClock.uptimeMillis();videoAvailable=true;
                    progress.setText(currentTitle);
                    double time=data.optDouble("time"),duration=data.optDouble("duration");
                    if(SystemClock.uptimeMillis()-lastSeek>1500){seekBar.setMax(Math.max(1,(int)(duration*1000)));seekBar.setKeyProgressIncrement(10000);seekBar.setProgress((int)(time*1000));elapsedTime.setText(clock(time));}
                    totalTime.setText(clock(duration));
                    JSONObject captions=data.optJSONObject("captions");if(captions!=null){captionTracks=captions.optJSONArray("list");selectedCaption=captions.optInt("selected",0);}
                    JSONObject skips=data.optJSONObject("skips");
                    boolean intro=skips!=null&&skips.optBoolean("intro"),outro=skips!=null&&skips.optBoolean("outro");
                    if(skipIntroButton.hasFocus()&&!intro||skipOutroButton.hasFocus()&&!outro)playButton.requestFocus();
                    skipIntroButton.setVisibility(intro?View.VISIBLE:View.GONE);skipOutroButton.setVisibility(outro?View.VISIBLE:View.GONE);
                    playButton.setText(data.optBoolean("paused")?"▶":"Ⅱ");
                    playButton.setContentDescription(data.optBoolean("paused")?"Play":"Pause");
                    if(firstVideo)scheduleHide();
                }catch(Exception ignored){}
            });
            WebViewCompat.addDocumentStartJavaScript(web,asset("playback.js").replace("__TOKEN__",UUID.randomUUID().toString()).replace("__SPEED__",Float.toString(playbackSpeed)),Collections.singleton("*"));
        }
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){if(!r.isForMainFrame())return false;return !"https".equals(r.getUrl().getScheme())||!"anikoto.cz".equals(r.getUrl().getHost());}
            @Override public void onPageCommitVisible(WebView v,String url){isolatePlayer();}
            @Override public void onPageFinished(WebView v,String url){isolatePlayer();}
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())progress.setText("Page unavailable. Press Back and try again.");}
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onShowCustomView(View view,CustomViewCallback callback){if(customView!=null){callback.onCustomViewHidden();return;}customView=view;customCallback=callback;player.addView(view,new FrameLayout.LayoutParams(-1,-1));controls.bringToFront();}
            @Override public void onHideCustomView(){closeCustom();}
        });
        controls=new FrameLayout(this);player.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(32),dp(20),dp(32),dp(34));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xE60B0D12,0x000B0D12}));
        controls.addView(top,new FrameLayout.LayoutParams(-1,dp(100),Gravity.TOP));
        progress=text("",22,WHITE);progress.setTypeface(null,1);progress.setSingleLine(true);progress.setEllipsize(android.text.TextUtils.TruncateAt.END);top.addView(progress,new LinearLayout.LayoutParams(0,-2,1));
        speedButton=compactButton("Speed "+speedLabel(),this::chooseSpeed);addCompact(top,speedButton,dp(102));
        Button episodesButton=compactButton("Episodes",()->chooseFrom(false));addCompact(top,episodesButton,dp(96));
        Button serverButton=compactButton("Server",()->chooseFrom(true));addCompact(top,serverButton,dp(86));
        subtitleButton=compactButton("Subtitles",this::chooseSubtitles);addCompact(top,subtitleButton,dp(96));
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(1);bottom.setPadding(dp(32),dp(34),dp(32),dp(22));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x000B0D12,0xF00B0D12}));
        controls.addView(bottom,new FrameLayout.LayoutParams(-1,dp(145),Gravity.BOTTOM));
        LinearLayout timeline=new LinearLayout(this);timeline.setGravity(Gravity.CENTER_VERTICAL);bottom.addView(timeline,new LinearLayout.LayoutParams(-1,dp(40)));
        elapsedTime=text("0:00",16,WHITE);elapsedTime.setGravity(Gravity.CENTER);timeline.addView(elapsedTime,new LinearLayout.LayoutParams(dp(64),-1));
        seekBar=new SeekBar(this);seekBar.setMax(1);seekBar.setKeyProgressIncrement(10000);seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(RED));seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(WHITE));
        timeline.addView(seekBar,new LinearLayout.LayoutParams(0,dp(40),1));
        seekBar.setOnFocusChangeListener((v,focused)->{seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(focused?RED:WHITE));seekBar.setScaleY(focused?1.2f:1f);});
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){lastSeek=SystemClock.uptimeMillis();elapsedTime.setText(clock(value/1000.0));command("seek:"+(value/1000.0));scheduleHide();}}
            public void onStartTrackingTouch(SeekBar bar){handler.removeCallbacks(hideDock);}
            public void onStopTrackingTouch(SeekBar bar){scheduleHide();}
        });
        totalTime=text("0:00",16,WHITE);totalTime.setGravity(Gravity.CENTER);timeline.addView(totalTime,new LinearLayout.LayoutParams(dp(64),-1));
        LinearLayout transport=new LinearLayout(this);transport.setGravity(Gravity.CENTER);bottom.addView(transport,new LinearLayout.LayoutParams(-1,dp(48)));
        Button rewindButton=compactButton("−10",()->command("rewind"));rewindButton.setContentDescription("Rewind 10 seconds");addCompact(transport,rewindButton,dp(64));
        playButton=compactButton("▶",()->command("toggle"));playButton.setTextSize(23);addCompact(transport,playButton,dp(64));
        Button forwardButton=compactButton("+10",()->command("forward"));forwardButton.setContentDescription("Forward 10 seconds");addCompact(transport,forwardButton,dp(64));
        // Explicit focus paths keep remote navigation between the three control rows predictable.
        for(View v:new View[]{rewindButton,playButton,forwardButton,seekBar,speedButton,episodesButton,serverButton,subtitleButton})v.setId(View.generateViewId());
        for(View v:new View[]{rewindButton,playButton,forwardButton})v.setNextFocusUpId(seekBar.getId());
        seekBar.setNextFocusDownId(playButton.getId());seekBar.setNextFocusUpId(speedButton.getId());
        seekBar.setOnKeyListener((v,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN){if(key==KeyEvent.KEYCODE_DPAD_UP){if(skipIntroButton.getVisibility()==View.VISIBLE)skipIntroButton.requestFocus();else if(skipOutroButton.getVisibility()==View.VISIBLE)skipOutroButton.requestFocus();else speedButton.requestFocus();}else playButton.requestFocus();}return true;}return false;
        });
        for(View v:new View[]{speedButton,episodesButton,serverButton,subtitleButton})v.setNextFocusDownId(seekBar.getId());
        LinearLayout skipRow=new LinearLayout(this);skipRow.setGravity(Gravity.RIGHT);
        FrameLayout.LayoutParams skipLayout=new FrameLayout.LayoutParams(-2,dp(40),Gravity.BOTTOM|Gravity.RIGHT);skipLayout.rightMargin=dp(36);skipLayout.bottomMargin=dp(120);controls.addView(skipRow,skipLayout);
        skipIntroButton=compactButton("Skip Intro",()->command("skip:intro"));addCompact(skipRow,skipIntroButton,dp(115));
        skipOutroButton=compactButton("Skip Outro",()->command("skip:outro"));addCompact(skipRow,skipOutroButton,dp(115));
        skipIntroButton.setId(View.generateViewId());skipOutroButton.setId(View.generateViewId());
        skipIntroButton.setVisibility(View.GONE);skipOutroButton.setVisibility(View.GONE);
        skipIntroButton.setNextFocusDownId(seekBar.getId());skipOutroButton.setNextFocusDownId(seekBar.getId());
        skipIntroButton.setNextFocusUpId(speedButton.getId());skipOutroButton.setNextFocusUpId(speedButton.getId());
        for(View v:new View[]{speedButton,episodesButton,serverButton,subtitleButton})v.setOnKeyListener((view,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_DOWN&&(skipIntroButton.getVisibility()==View.VISIBLE||skipOutroButton.getVisibility()==View.VISIBLE)){
                if(event.getAction()==KeyEvent.ACTION_DOWN)(skipIntroButton.getVisibility()==View.VISIBLE?skipIntroButton:skipOutroButton).requestFocus();return true;
            }return false;
        });
        selectionPane=new LinearLayout(this);selectionPane.setOrientation(1);selectionPane.setPadding(dp(50),dp(40),dp(50),dp(24));selectionPane.setBackgroundColor(BG);
        player.addView(selectionPane,new FrameLayout.LayoutParams(-1,-1));
        selectionTitle=text("Choose how to watch",32,WHITE);selectionTitle.setTypeface(null,1);selectionPane.addView(selectionTitle);
        selectionHint=text("Select an episode, then choose a SUB or DUB server to start.",18,0xFFADB0BF);
        LinearLayout.LayoutParams hintLayout=new LinearLayout.LayoutParams(-1,dp(60));hintLayout.topMargin=dp(15);selectionPane.addView(selectionHint,hintLayout);
        LinearLayout selectionNav=new LinearLayout(this);selectionPane.addView(selectionNav);
        episodeButton=button("Loading episodes…",()->chooseFrom(false));selectionNav.addView(episodeButton,new LinearLayout.LayoutParams(dp(270),dp(50)));
        addButton(selectionNav,"Back to browse",this::closePlayer);
        ScrollView choicesScroll=new ScrollView(this);selectionPane.addView(choicesScroll,new LinearLayout.LayoutParams(-1,0,1));
        serverChoices=new LinearLayout(this);serverChoices.setOrientation(1);serverChoices.setPadding(0,dp(25),0,0);choicesScroll.addView(serverChoices);
        if(!scripts)progress.setText("Update Android System WebView to enable TV playback controls.");
    }
    private Button compactButton(String label,Runnable action){Button b=button(label,action);b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(6),0,dp(6),0);return b;}
    private void addCompact(LinearLayout row,Button b,int width){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,dp(40));p.setMargins(dp(5),0,dp(5),0);row.addView(b,p);}
    private String clock(double seconds){int n=Math.max(0,(int)seconds);return n>=3600?String.format(Locale.US,"%d:%02d:%02d",n/3600,n/60%60,n%60):String.format(Locale.US,"%d:%02d",n/60,n%60);}
    private void chooseSubtitles(){
        if(captionTracks==null||captionTracks.length()<=1){Toast.makeText(this,"This server has no selectable subtitles yet. Try another server.",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[captionTracks.length()];for(int i=0;i<labels.length;i++)labels[i]=captionTracks.optString(i,"Track "+i);
        speedDialogOpen=true;handler.removeCallbacks(hideDock);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Subtitles").setSingleChoiceItems(labels,selectedCaption,(d,index)->{command("captions:"+index);selectedCaption=index;d.dismiss();}).setNegativeButton("Cancel",null).create();
        dialog.setOnDismissListener(d->{speedDialogOpen=false;subtitleButton.requestFocus();scheduleHide();});dialog.show();
    }
    private String speedLabel(){return (playbackSpeed==(int)playbackSpeed?Integer.toString((int)playbackSpeed):Float.toString(playbackSpeed))+"×";}
    private void chooseSpeed(){
        float[] rates={0.5f,0.75f,1f,1.25f,1.5f,1.75f,2f};String[] labels={"0.5×","0.75×","1× (Normal)","1.25×","1.5×","1.75×","2×"};int selected=2;
        for(int i=0;i<rates.length;i++)if(rates[i]==playbackSpeed)selected=i;
        speedDialogOpen=true;handler.removeCallbacks(hideDock);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(labels,selected,(d,index)->{
            playbackSpeed=rates[index];getPreferences(0).edit().putFloat("playbackSpeed",playbackSpeed).apply();
            speedButton.setText("Speed "+speedLabel());command("speed:"+playbackSpeed);d.dismiss();
        }).setNegativeButton("Cancel",null).create();
        dialog.setOnDismissListener(d->{speedDialogOpen=false;speedButton.requestFocus();scheduleHide();});dialog.show();
    }
    private void openAnime(Anime anime){
        hideKeyboard();handler.removeCallbacks(liveSearch);
        currentTitle=anime.title;currentUrl=anime.url;episodes=new JSONArray();servers=new JSONArray();captionTracks=new JSONArray();videoAvailable=false;lastVideo=0;playingScreen=true;expectedEpisode="";
        home.setVisibility(View.GONE);player.setVisibility(View.VISIBLE);progress.setText(currentTitle+" • Loading episode…");web.onResume();web.loadUrl(anime.url);showSelection();
    }
    private void showSelection(){
        handler.removeCallbacks(hideDock);
        awaitingChoice=true;serverSignature="";controls.setVisibility(View.GONE);selectionPane.setVisibility(View.VISIBLE);selectionPane.bringToFront();
        selectionTitle.setText(currentTitle);selectionHint.setText("Select an episode, then choose a SUB or DUB server to start.");
        serverChoices.removeAllViews();serverChoices.addView(text("Loading available servers…",20,0xFFADB0BF));
        episodeButton.setText(episodes.length()>0?"Choose episode":"Loading episodes…");episodeButton.requestFocus();
    }
    private void renderServerChoices(){
        if(!awaitingChoice)return;
        String signature=servers.toString();if(signature.equals(serverSignature))return;serverSignature=signature;
        serverChoices.removeAllViews();
        if(servers.length()==0){serverChoices.addView(text("Loading servers for this episode…",20,0xFFADB0BF));return;}
        for(String type:new String[]{"SUB","DUB","OTHER"}){
            LinearLayout line=new LinearLayout(this);boolean any=false;
            for(int i=0;i<servers.length();i++){
                JSONObject server=servers.optJSONObject(i);String group=server.optString("group","OTHER");if(!type.equals(group))continue;
                final int index=i;addButton(line,server.optString("label"),()->startServer(index));any=true;
            }
            if(any){TextView label=text(type.equals("SUB")?"SUBTITLED":type.equals("DUB")?"DUBBED AUDIO":"SERVERS",18,0xFFADB0BF);label.setPadding(0,dp(15),0,dp(8));serverChoices.addView(label);serverChoices.addView(line);}
        }
        selectionHint.setText("Choose a server below to start. If one fails, try another.");
        if(episodeButton.hasFocus()){
            for(int i=0;i<serverChoices.getChildCount();i++)if(serverChoices.getChildAt(i) instanceof LinearLayout){((LinearLayout)serverChoices.getChildAt(i)).getChildAt(0).requestFocus();break;}
        }
    }
    private void startServer(int index){
        awaitingChoice=false;selectionPane.setVisibility(View.GONE);videoAvailable=false;
        JSONObject choice=servers.optJSONObject(index);progress.setText(currentTitle+" • "+choice.optString("group")+" • "+choice.optString("label")+" • Starting…");
        web.evaluateJavascript("(function(){var e=document.querySelectorAll('#w-servers li[data-ep-id],#w-servers li[data-link-id]')["+index+"];if(e)e.click();})()",null);
        showControls();
        handler.postDelayed(()->{if(playingScreen&&!awaitingChoice)command("play");},700);
    }
    private void command(String action){
        if ((action.equals("toggle") || action.equals("play")) && (!videoAvailable || SystemClock.uptimeMillis()-lastVideo>6000)) {
            long now=SystemClock.uptimeMillis();
            MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,web.getWidth()/2f,web.getHeight()/2f,0);
            MotionEvent up=MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,web.getWidth()/2f,web.getHeight()/2f,0);
            web.dispatchTouchEvent(down);web.dispatchTouchEvent(up);down.recycle();up.recycle();
            progress.setText(currentTitle+" • Starting player…");
        }
        web.evaluateJavascript("window.__tvCommand && window.__tvCommand('"+action+"')",null);
        if((!videoAvailable||SystemClock.uptimeMillis()-lastVideo>6000) && !action.equals("play") && !action.equals("toggle") && !action.equals("pause"))Toast.makeText(this,"Video is not ready. Try another server in Sub / Dub.",Toast.LENGTH_LONG).show();
    }
    private void showControls(){if(awaitingChoice){selectionPane.bringToFront();return;}controls.setVisibility(View.VISIBLE);controls.bringToFront();playButton.requestFocus();web.evaluateJavascript("window.__tvCommand&&window.__tvCommand('overlay:on')",null);scheduleHide();}
    private void hideControls(){handler.removeCallbacks(hideDock);controls.setVisibility(View.GONE);web.evaluateJavascript("window.__tvCommand&&window.__tvCommand('overlay:off')",null);web.requestFocus();}
    private void closeCustom(){if(customView!=null){player.removeView(customView);customView=null;if(customCallback!=null)customCallback.onCustomViewHidden();customCallback=null;}}
    private void closePlayer(){command("pause");closeCustom();playingScreen=false;awaitingChoice=false;web.loadUrl("about:blank");player.setVisibility(View.GONE);home.setVisibility(View.VISIBLE);if(lastCard!=null)lastCard.requestFocus();}
    private void isolatePlayer(){
        web.evaluateJavascript("(function(){if(!document.getElementById('tv-player-style')){var s=document.createElement('style');s.id='tv-player-style';s.textContent='html,body{margin:0!important;overflow:hidden!important;background:black!important} body *{visibility:hidden!important} #player,#player *{visibility:visible!important} #player{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:999999!important} #player iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;border:0!important}';document.head.appendChild(s);}})()",null);
    }
    private void inspectPlayer(){
        isolatePlayer();
        web.evaluateJavascript("window.__tvCommand&&window.__tvCommand('speed:"+playbackSpeed+"')",null);
        web.evaluateJavascript("window.__tvCommand&&window.__tvCommand('overlay:"+(controls.getVisibility()==View.VISIBLE&&!awaitingChoice?"on":"off")+"')",null);
        String js="(function(){function list(selector){return Array.from(document.querySelectorAll(selector)).map(function(e,i){var t=e.querySelector('.d-title');return {index:i,id:e.getAttribute('data-id')||e.getAttribute('data-ep-id')||'',label:e.getAttribute('data-num')?'Episode '+e.getAttribute('data-num')+(t&&!/^Episode /.test(t.textContent)?' • '+t.textContent.trim():''):e.textContent.trim().replace(/\\s+/g,' ').slice(0,90),active:e.classList.contains('active'),group:e.closest('.type[data-type]')?e.closest('.type[data-type]').getAttribute('data-type').toUpperCase():'OTHER'};});}return JSON.stringify({episodes:list('#w-episodes a[data-num],#w-episodes .episodes a'),servers:list('#w-servers li[data-ep-id],#w-servers li[data-link-id]')});})()";
        web.evaluateJavascript(js,result->{try{
            String json=(String)new JSONTokener(result).nextValue();JSONObject data=new JSONObject(json);episodes=data.getJSONArray("episodes");JSONArray available=data.getJSONArray("servers");
            if(expectedEpisode.isEmpty()||available.length()==0||expectedEpisode.equals(available.optJSONObject(0).optString("id")))servers=available;
            for(int i=0;i<episodes.length();i++)if(episodes.optJSONObject(i).optBoolean("active"))episodeButton.setText(episodes.optJSONObject(i).optString("label"));
            renderServerChoices();
        }catch(Exception ignored){}});
    }
    private void chooseFrom(boolean server){
        if(server){command("pause");showSelection();renderServerChoices();return;}
        JSONArray list=server?servers:episodes;
        if(list.length()==0){Toast.makeText(this,"Still loading "+(server?"servers":"episodes")+". Try again in a moment.",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[list.length()];int active=0;
        for(int i=0;i<list.length();i++){JSONObject item=list.optJSONObject(i);labels[i]=item.optString("label");if(item.optBoolean("active"))active=i;}
        new AlertDialog.Builder(this).setTitle(server?"Audio and server":"Choose episode").setSingleChoiceItems(labels,active,(dialog,index)->{
            String selector=server?"#w-servers li[data-ep-id],#w-servers li[data-link-id]":"#w-episodes a[data-num],#w-episodes .episodes a";
            web.evaluateJavascript("(function(){var e=document.querySelectorAll('"+selector+"')["+index+"];if(e)e.click();})()",null);
            expectedEpisode=list.optJSONObject(index).optString("id");servers=new JSONArray();videoAvailable=false;progress.setText(currentTitle+" • Loading "+labels[index]);dialog.dismiss();showSelection();
        }).setNegativeButton("Cancel",null).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(!playingScreen)return super.dispatchKeyEvent(event);
        if(awaitingChoice)return super.dispatchKeyEvent(event);
        int key=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(down)scheduleHide();
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE){if(down)command(key==KeyEvent.KEYCODE_MEDIA_PLAY?"play":key==KeyEvent.KEYCODE_MEDIA_PAUSE?"pause":"toggle");return true;}
        if(key==KeyEvent.KEYCODE_MENU){if(down)showControls();return true;}
        if(controls.getVisibility()!=View.VISIBLE){
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){if(down){showControls();command(key==KeyEvent.KEYCODE_DPAD_LEFT?"rewind":"forward");}return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(down)showControls();return true;}
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(playingScreen)closePlayer();else if(searchMode||scheduleScreen||!"/home".equals(Uri.parse(catalogUrl).getPath())){leaveSearch();loadCatalog("https://anikoto.cz/home");}else new AlertDialog.Builder(this).setMessage("Exit Anikoto TV?").setPositiveButton("Exit",(d,w)->finish()).setNegativeButton("Stay",null).show();}
    @Override protected void onPause(){if(web!=null){web.evaluateJavascript("window.__tvCommand&&window.__tvCommand('pause')",null);web.onPause();}super.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);network.shutdownNow();catalogNetwork.shutdownNow();if(web!=null){player.removeView(web);web.destroy();}images.evictAll();super.onDestroy();}
}
