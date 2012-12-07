package org.ender.wiki;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class Wiki {
    private static final Pattern PAT_CATS = Pattern.compile("\\{\\{([^\\|]*)(|.*?)}}", Pattern.MULTILINE|Pattern.DOTALL);
    private static final Pattern PAT_ARGS = Pattern.compile("\\s*\\|\\s*(.*?)\\s*=\\s*([^\\|]*)", Pattern.MULTILINE|Pattern.DOTALL);
    private static final String CONTENT_URL = "action=query&prop=revisions&titles=%s&rvprop=content&format=json";

    static private final Map<String, String> imap = new HashMap<String, String>(15);
    static private final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<String>();
    static private File folder;
    private static final Map<String, Item> DB = new LinkedHashMap<String, Item>(9, 0.75f, true) {
	private static final long serialVersionUID = 1L;
	@Override
	protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
	    return false;
	}
    };

    public static void init(File cfg, int workers){
	imap.put("Arts & Crafts", "arts");
	imap.put("Cloak & Dagger", "cloak");
	imap.put("Faith & Wisdom", "faith");
	imap.put("Frontier & Wilderness", "wild");
	imap.put("Hammer & Nail", "nail");
	imap.put("Hunting & Gathering", "hung");
	imap.put("Law & Lore", "law");
	imap.put("Mines & Mountains", "mine");
	imap.put("Pots & Pans", "pots");
	imap.put("Sparks & Embers", "fire");
	imap.put("Stocks & Cultivars", "stock");
	imap.put("Sugar & Spice", "spice");
	imap.put("Thread & Needle", "thread");
	imap.put("Natural Philosophy", "natp");
	imap.put("Perennial Philosophy", "perp");

	folder = cfg;
	if(!folder.exists()){folder.mkdirs();}

	for(int i=0; i<workers; i++){
	    Thread t = new Thread(new Runnable() {

		@Override
		public void run() {
		    while(true){
			try {
			    load(requests.take());
			} catch (InterruptedException e) {
			    e.printStackTrace();
			}
		    }
		}
	    }, "Wiki loader "+i);
	    t.setDaemon(true);
	    t.start();
	}
    }

    public static Item get(String name){
	Item itm = null;
	synchronized (DB) {
	    if(DB.containsKey(name)){
		return DB.get(name);
	    }
	    itm = get_cache(name, true);
	    DB.put(name, itm);
	}
	request(name);
	return itm;
    }

    private static void request(String name) {
	try {
	    requests.put(name);
	} catch (InterruptedException e) {
	    e.printStackTrace();
	}
    }

    private static void store(String label, Item item) {
	synchronized (DB) {
	    DB.put(label, item);
	}
	//System.out.println(item.toString());
    }

    private static void cache(Item item) {
	FileWriter fw;
	try {
	    fw = new FileWriter(new File(folder, item.name+".xml"));
	    fw.write(item.toXML());
	    fw.close();
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    public static String stream2str(java.io.InputStream is) {
	java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
	return s.hasNext() ? s.next() : "";
    }

    private static void load(String name){
	//System.out.println(String.format("Loading '%s' at '%s'", name, Thread.currentThread().getName()));
	Item item = get_cache(name, false);
	if(item == null){
	    item = new Item();
	    item.name = name;
	    item.content = get_content(name);
	    if(item.content != null){
		parse_content(item);
	    }

	    cache(item);
	}
	store(name, item);
	//System.out.println(String.format("Finished '%s' at '%s'", name, Thread.currentThread().getName()));
    }

    private static void parse_content(Item item) {
	Matcher m = PAT_CATS.matcher(item.content);
	while(m.find()){
	    if(m.groupCount() == 2){
		String method = m.group(1).trim();
		String argsline = m.group(2);
		parse(item, method, getargs(argsline));
	    }

	    item.content = m.replaceFirst("");
	    m = PAT_CATS.matcher(item.content);
	}
    }

    private static void parse(Item item, String method, Map<String, String> args) {
	if(method.equals("Crafted")){
	    String reqs = args.get("Objects required");
	    System.out.println(reqs);
	} else if(method.equals("Inspirational")){
	    Map<String, Integer> attrs = new HashMap<String, Integer>();
	    for(Entry<String, String> e : args.entrySet()){
		try {
		    attrs.put(imap.get(e.getKey()), Integer.parseInt(e.getValue()));
		} catch (NumberFormatException ex){}
	    }
	    item.attgive = attrs;
	} else if(method.equals("Food")){
	    
	} else {
	    System.out.println(String.format("Item '%s': Unknown method '%s', args: %s",item.name, method, args.toString()));
	}
    }

    private static Map<String, String> getargs(String argsline) {
	Map<String, String> args = new HashMap<String, String>();
	Matcher m = PAT_ARGS.matcher(argsline);
	while(m.find()){
	    if(m.groupCount() == 2){
		String name = m.group(1).trim();
		String val = m.group(2).trim();
		args.put(name,  val);
	    }
	}
	return args;
    }

    private static String get_content(String name){
	String content = null;
	try {
	    URI uri = new URI("http", null, "salemwiki.info", -1, "/api.php", String.format(CONTENT_URL, name), null);

	    URL link = uri.toURL();
	    String data = stream2str(link.openStream());
	    JSONObject json = new JSONObject(data);
	    json = json.getJSONObject("query").getJSONObject("pages");
	    String pageid = JSONObject.getNames(json)[0];
	    content = json.getJSONObject(pageid).getJSONArray("revisions").getJSONObject(0).getString("*");
	    return content;
	} catch (JSONException e) {
	    System.err.println(String.format("Error while parsing '%s':\n%s\nContent:'%s'", name, e.getMessage(), content));
	} catch (IOException e) {
	    e.printStackTrace();
	} catch (URISyntaxException e) {
	    e.printStackTrace();
	}
	return null;
    }

    private static Item get_cache(String name, boolean fast) {
	File f = new File(folder, name+".xml");
	if(!f.exists()){return null;}
	if(!fast && has_update(name, f.lastModified())){
	    return null;
	}
	return load_cache(f);
    }

    private static Item load_cache(File f) {
	try {
	    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	    DocumentBuilder builder = factory.newDocumentBuilder();
	    Document doc = builder.parse(f);
	    Item item = new Item();
	    item.name = doc.getDocumentElement().getAttribute("name");

	    item.required = parse_cache(doc, "required");
	    item.locations = parse_cache(doc, "locations");
	    item.reqby = parse_cache(doc, "reqby");
	    item.tech = parse_cache(doc, "tech");
	    item.unlocks = parse_cache(doc, "unlocks");
	    item.attreq = parse_cache_map(doc, "attreq");
	    item.attgive = parse_cache_map(doc, "attgive");

	    return item;
	} catch (MalformedURLException e) {
	    e.printStackTrace();
	} catch (IOException e) {
	    e.printStackTrace();
	} catch (SAXException e) {
	    e.printStackTrace();
	} catch (ParserConfigurationException e) {
	    e.printStackTrace();
	}
	return null;
    }

    private static Set<String> parse_cache(Document doc, String tag) {
	NodeList list = doc.getElementsByTagName(tag);
	if(list.getLength() > 0){
	    Set<String> items = new HashSet<String>(list.getLength());
	    for(int i=0; i< list.getLength(); i++){
		items.add(list.item(i).getAttributes().getNamedItem("name").getNodeValue());
	    }
	    return items;
	}
	return null;
    }

    private static Map<String, Integer> parse_cache_map(Document doc, String tag) {
	NodeList list = doc.getElementsByTagName(tag);
	if(list.getLength() > 0){
	    Node item = list.item(0);
	    Map<String, Integer> items = new HashMap<String, Integer>();
	    NamedNodeMap attrs = item.getAttributes();
	    for(int i=0; i< attrs.getLength(); i++){
		Node attr = attrs.item(i);
		items.put(attr.getNodeName(), Integer.decode(attr.getNodeValue()));
	    }
	    return items;
	}
	return null;
    }

    private static boolean has_update(String name, long date) {
	try {
	    //String p = String.format("%s%s", WIKI_URL, name);
	    URI uri = new URI("http","salemwiki.info","/index.php/"+name, null);
	    URL  url = uri.toURL();
	    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
	    conn.setRequestMethod("HEAD");
	    conn.setIfModifiedSince(date);
	    //conn.disconnect();
	    if(conn.getResponseCode() == HttpURLConnection.HTTP_OK){
		return true;
	    }
	} catch (MalformedURLException e) {
	    e.printStackTrace();
	} catch (IOException e) {
	    e.printStackTrace();
	} catch (URISyntaxException e1) {
	    e1.printStackTrace();
	}
	return false;
    }

}
