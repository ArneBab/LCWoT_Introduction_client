package plugins.lcwot.introduction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;
import java.util.TimeZone;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.db4o.ObjectContainer;

import plugins.lcwot.introduction.storage.Ramstore;
import plugins.lcwot.introduction.storage.Ramstore_ident;
import plugins.lcwot.introduction.storage.Ramstore_ownIdent;
import plugins.lcwot.introduction.storage.Ramstore_puzzle;
import plugins.lcwot.introduction.ui.Introducer_Toadlet;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.client.async.BaseClientPutter;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.ClientPutCallback;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.node.RequestClient;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginTalker;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginRespirator;
import freenet.pluginmanager.PluginTalker;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.Closer;

public class Worker extends Thread implements FredPluginTalker, FredPluginL10n, ClientGetCallback, ClientPutCallback, RequestClient  {
	private final PluginRespirator pr;
	private boolean running = false;
	private PluginTalker plugintalker;
	private final Ramstore storage;
	// TODO: move following to Ramstore
	private final static long max_thread_wait_time = 1000 * 60 * 10;
	private final static int wot_max_age_days = 2;
	private final static long wot_max_age = 1000 * 60 * 60 * 24 * wot_max_age_days;
	private final static long wot_puzzle_required_validity = 1000 * 60 * 60 * 2;
	private final static int wot_puzzle_max_size = 16384;
	private final static int wot_puzzle_max_download = 40;
	public final static int wot_ui_max_puzzles_per_page = 20;
	private final static long wot_required_time_between_puzzles_from_a_single_identity = 1000 * 60 * 60 * 24;
	private final static int max_puzzle_uris = wot_puzzle_max_download * 2;
	public final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
	
	private final ToadletContainer mFredWebUI;
	private final Toadlet introducer_toadlet;
	private final ArrayDeque<SimpleFieldSet> queue_plugintalker;
	private final ArrayDeque<FreenetURI> queue_puzzlefetcher;
	private final ArrayDeque<Ramstore_puzzle> queue_puzzleinserter;
	private boolean waiting_for_identity_refresh = false;
	private final HighLevelSimpleClient hlsc;
	private final FetchContext fetchcontext;
	private final InsertContext insertcontext;
	private int concurrentFetchers = 0;
	private int concurrentInserters = 0;
	private final Random rng;
	private long nextMaintenance = 0;
	private final HashMap<String, ClientGetter> runningFetches; // TODO hashmap needed? clientgetter from hlsc == state from response?
	private final DocumentBuilderFactory xmlFactory;
	private DocumentBuilder xmlDocumentBuilder;
	private final DOMImplementation xmlDOM;
	private Transformer xmlSerializer;
	private final SimpleFieldSet sfs_ownidents;
	private final SimpleFieldSet sfs_remoteidents;

	public Ramstore getStorage() {
		return this.storage;
	}
	public boolean isRunning() {
		return this.running;
	}
	/**
	 * Thread which handles various tasks:
	 * <ul>
	 * <li>register the {@link Introducer_Toadlet} once WebOfTrust is available</li>
	 * <li>maintain a list of identities from WebOfTrust via {@link PluginTalker} which have been fetched recently</li>
	 * <li>maintain a backlog of {@link max_puzzle_uris} possible puzzle URIs to fetch</li>
	 * <li>continuously fetch up to {@link concurrentFetchers} puzzles at once until {@link wot_puzzle_max_download} is reached</li>
	 * <li>continuously insert up to {@link concurrentInserters} puzzle solutions at once</li>
	 * <li>parse puzzle XML documents and create puzzle solution XML documents</li>
	 * <li>perform maintenance in {@link max_thread_wait_time} interval (update identities and clean puzzles which have been expired)</li>
	 * </ul>
	 * @author SeekingFor
	 */
	public Worker(PluginRespirator pr) {
		// own stuff
		this.storage = new Ramstore();
		this.queue_plugintalker = new ArrayDeque<SimpleFieldSet>();
		this.queue_puzzlefetcher= new ArrayDeque<FreenetURI>();
		this.queue_puzzleinserter = new ArrayDeque<Ramstore_puzzle>();
		this.runningFetches = new HashMap<String, ClientGetter>(Ramstore.max_concurrentFetchCount);
		Worker.sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		this.rng = new Random();
		
		// Freenet stuff
		this.pr = pr;
		this.hlsc = pr.getNode().clientCore.makeClient(Ramstore.fetchpriority, false, true);
		this.fetchcontext = this.hlsc.getFetchContext();
		this.fetchcontext.filterData = false;
		this.fetchcontext.followRedirects = true; // hm??
		this.fetchcontext.maxArchiveLevels = 0;
		this.fetchcontext.maxNonSplitfileRetries = 2;
		this.fetchcontext.maxOutputLength = Worker.wot_puzzle_max_size;
		this.insertcontext = this.hlsc.getInsertContext(true);
		this.insertcontext.maxInsertRetries = -1;
		this.mFredWebUI = pr.getToadletContainer();
		this.introducer_toadlet = new Introducer_Toadlet(this, "/WebOfTrust/introducer", pr.getHLSimpleClient());

		// XML stuff
		this.xmlFactory = DocumentBuilderFactory.newInstance();
		try { this.xmlFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		} catch (ParserConfigurationException e) {
			System.out.println("ERROR: failed to setFeature for xmlFactory: " + e.getMessage());
		}
		this.xmlFactory.setAttribute("http://apache.org/xml/features/disallow-doctype-decl", true);
		try { this.xmlDocumentBuilder = xmlFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			System.out.println("ERROR: failed to load XML documentBuilder: " + e.getMessage());
		}
		this.xmlDOM = this.xmlDocumentBuilder.getDOMImplementation();
		try { this.xmlSerializer = TransformerFactory.newInstance().newTransformer();
		} catch (Exception e) {
			System.out.println("ERROR: failed to load XML serializer: " + e.getMessage());
		}
		this.xmlSerializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
		this.xmlSerializer.setOutputProperty(OutputKeys.INDENT, "yes"); // TODO: Disable as soon as bug 0004850 is fixed.
		this.xmlSerializer.setOutputProperty(OutputKeys.STANDALONE, "no");

		// PluginTalker messages
		sfs_ownidents = new SimpleFieldSet(false);
		sfs_ownidents.putOverwrite("Message", "GetOwnIdentities");
		sfs_remoteidents = new SimpleFieldSet(false);
		sfs_remoteidents.putOverwrite("Message", "GetIdentitiesByScore");
		sfs_remoteidents.putOverwrite("Truster", "null");
		sfs_remoteidents.putOverwrite("Selection", "+");
		sfs_remoteidents.putOverwrite("Context", "Introduction");
	}
	
	public void terminate() {
		// FIXME figure out why fred sometimes fails to terminate plugin.
		// ^ likely needs a synchronized void here
		System.out.println("trying to terminate");
		try {
			mFredWebUI.unregister(introducer_toadlet);
		} catch (NullPointerException e) {
			// ignore, Toadlet was never registered
		}
		this.running = false;
		System.out.println("interrupting");
		this.interrupt();
		System.out.println("canceling running fetches");
		synchronized (this.runningFetches) {
			for (ClientGetter getter : this.runningFetches.values()) {
				getter.cancel(null, this.pr.getNode().clientCore.clientContext);
			}
		}
		System.out.println("running fetches cancled");
		
	}
	
	@Override
	public void run() {
		this.running = true;
		// try to establish PluginTalker connection with WebOfTrust
		while (this.plugintalker == null && this.running && ! this.isInterrupted()) {
			try {
				this.plugintalker = this.pr.getPluginTalker(this, "plugins.WebOfTrust.WebOfTrust", "lcwot-introduction");
			} catch (PluginNotFoundException e) {
				System.out.println(e.getMessage());
				try {
					sleep(5*1000);
				} catch (InterruptedException e1) { 
					System.out.println("sleep interrupted.");
				}
			}	
		}
		// register Toadlet
		if(this.running && ! this.isInterrupted()) {
			mFredWebUI.register(introducer_toadlet, "WebOfTrust.menuName.name", introducer_toadlet.path(), true, "Introducer", "Gather some trust", true, null, this);
		}
		long now;
		while(this.running && ! this.isInterrupted()) {
			now = System.currentTimeMillis();
			try {
				synchronized(this) {
					if (
						// no plugintalker replies from WebOfTrust plugin available
						this.queue_plugintalker.size() == 0 &&
						(	// don't need to fetch more puzzles or max of concurrent fetches reached
							this.storage.puzzles.size() >= Worker.wot_puzzle_max_download ||
							this.concurrentFetchers == Ramstore.max_concurrentFetchCount
						) &&
						(	// don't need to insert solutions or max of concurrent inserts reached
							this.queue_puzzleinserter.size() == 0 ||
							this.concurrentInserters == Ramstore.max_concurrentInsertCount
						) &&
						(	// still have identity puzzle URIs in pool or waiting for identity update reply from WebOfTrust 
							this.queue_puzzlefetcher.size() > 0 ||
							this.waiting_for_identity_refresh
						) &&
						// no maintenance required
						this.nextMaintenance > now
					) {
						// wait until something happens or next maintenance is scheduled
						this.wait(this.nextMaintenance - now);
					}
					// do maintenance
					if(this.nextMaintenance < System.currentTimeMillis()) {
						System.out.println("scheduled maintenance");
						now = System.currentTimeMillis();
						// remove expired puzzles
						synchronized(this.storage.puzzles) {
							Iterator<Ramstore_puzzle> iter = this.storage.puzzles.values().iterator();
							Ramstore_puzzle puzzle;
							while(iter.hasNext()) {
								puzzle = iter.next();
								if(puzzle.valid_until - now < Worker.wot_puzzle_required_validity) {
									iter.remove();
									System.out.println("removed expired puzzle");
								}
							}
						}
						// clean puzzle URIs
						this.queue_puzzlefetcher.clear();
						// update own identities
						this.plugintalker.send(this.sfs_ownidents, null);
						// update other identities
						this.plugintalker.send(this.sfs_remoteidents, null);
						this.waiting_for_identity_refresh = true;
						// schedule next maintenance
						this.nextMaintenance = System.currentTimeMillis() + Worker.max_thread_wait_time;
						System.out.println("next maintenance scheduled for " + new Date(this.nextMaintenance).toString());
					}
					// send PluginTalker identity update request
					if(this.queue_puzzlefetcher.size() == 0 && ! this.waiting_for_identity_refresh) {
						System.out.println("refreshing remote identities in non maintenance mode");
						this.plugintalker.send(this.sfs_remoteidents, null);
						this.waiting_for_identity_refresh = true;
					}
					// parse PluginTalker reply
					while (this.queue_plugintalker.size() > 0) {
						this.parse_plugintalker_reply(this.queue_plugintalker.pollFirst());
					}
					// fetch puzzles
					while (
						this.storage.puzzles.size() <= Worker.wot_puzzle_max_download &&
						this.concurrentFetchers < Ramstore.max_concurrentFetchCount &&
						this.queue_puzzlefetcher.size() > 0
					) {
						try {
							ClientGetter getter = this.hlsc.fetch(this.queue_puzzlefetcher.pollFirst(), this, this, this.fetchcontext, Ramstore.fetchpriority);
							synchronized(this.runningFetches) {
								this.runningFetches.put(getter.getURI().toString(), getter);
							}
							this.concurrentFetchers ++;
						} catch (FetchException e) {
							System.out.println(e.getMessage());
							e.printStackTrace();
						}
					}
					// insert puzzle solutions
					while(
						this.queue_puzzleinserter.size() > 0 &&
						this.concurrentInserters < Ramstore.max_concurrentInsertCount
					) {
						Ramstore_puzzle puzzle = this.queue_puzzleinserter.pollFirst();
						final InsertBlock ib = getInsertBlockFromPuzzle(puzzle);
						if(ib != null) { 
							try {
								this.hlsc.insert(ib, false, null, false, this.insertcontext, this, Ramstore.insertpriority);
								this.concurrentInserters ++;
							} catch (InsertException e) {
								System.out.println("[InsertException] while trying to insert solution: " + e.getMessage());
							}
						} else {
							System.out.println("error while creating InsertBlock, skipping solution.");
						}
					}
				}
			} catch (InterruptedException e) {
				System.out.println("Thread was interrupted");
				System.out.println("Worker is supposed to shut down");
			}
		}
		System.out.println("should backup stuffs now");
		System.out.println("bye");
	}
	
	private final InsertBlock getInsertBlockFromPuzzle(Ramstore_puzzle puzzle) {
		String insert_key = "KSK@WebOfTrust|Introduction|" + puzzle.uid_routingkey + "|" + puzzle.solution;
		System.out.println("starting to insert solution: " + insert_key);
		Bucket tmpBucket = null;
		OutputStream os = null;
		Document xmlDoc;
		try {
			tmpBucket = this.pr.getNode().clientCore.tempBucketFactory.makeBucket(1024);
			os = tmpBucket.getOutputStream();
			xmlDoc = this.xmlDOM.createDocument(null, "WebOfTrust", null);
			xmlDoc.setXmlVersion("1.1");
			Element rootElement = xmlDoc.getDocumentElement();
			rootElement.setAttribute("Version", Long.toString(Ramstore.wot_version));
			Element introElement = xmlDoc.createElement("IdentityIntroduction");
			introElement.setAttribute("Version", Integer.toString(Ramstore.wot_xml_introduction_version));
			Element identityElement = xmlDoc.createElement("Identity");
			identityElement.setAttribute("URI", puzzle.solvedBy);
			introElement.appendChild(identityElement);
			rootElement.appendChild(introElement);
			DOMSource domSource = new DOMSource(xmlDoc);
			StreamResult resultStream = new StreamResult(os);
			this.xmlSerializer.transform(domSource, resultStream);
			os.close();
			os = null;
			tmpBucket.setReadOnly();
			Closer.close(os);
			return new InsertBlock(tmpBucket, null, new FreenetURI(insert_key));
		} catch (IOException e) {
			System.out.println("[IPException] while trying to insert solution: " + e.getMessage());
			e.printStackTrace();
		} catch (TransformerException e) {
			System.out.println("[TransformerException] while trying to insert solution: " + e.getMessage());
			e.printStackTrace();
		} finally {
			Closer.close(os);
			// FIXME this seems to free the bucket while the insert actually takes place
			// ^ which means the insert succeeds but result is an empty file
			//Closer.close(tmpBucket);
		}
		return null;
	}
	public synchronized void request_local_identity_update() {
		this.plugintalker.send(this.sfs_ownidents, null);
	}
	
	public synchronized void addSolution(Ramstore_puzzle puzzle) {
		this.queue_puzzleinserter.addLast(puzzle);
		this.notify();
	}
	
	@Override
	public synchronized void onReply(String pluginname, String indentifier, SimpleFieldSet params, Bucket data) {
		this.queue_plugintalker.addLast(params);
		this.notify();
	}

	private void parse_plugintalker_reply(SimpleFieldSet params) {
		if(params.get("Message").equals("OwnIdentities")) {
			Iterator<String> iter = params.keyIterator();
			String key;
			FreenetURI request_uri;
			String nick;
			Ramstore_ownIdent ident;
			while(iter.hasNext()) {
				key = iter.next();
				if(key.startsWith("RequestURI")) {
					try {
						request_uri = new FreenetURI(params.get(key));
						nick = params.get(key.replace("RequestURI", "Nickname"));
						ident = new Ramstore_ownIdent(nick, request_uri);
						//System.out.println("found own identity: " + ident.toString());
						synchronized(this.storage.ownidents) {
							if(!this.storage.ownidents.contains(ident)) {
								this.storage.ownidents.add(ident);;
								System.out.println("added a new " + ident);		
							} else {
								ident = this.storage.ownidents.get(this.storage.ownidents.indexOf(ident)); 
								ident.request_uri = request_uri;
								ident.nick = nick;
							}
						}
					} catch (MalformedURLException e) {
						System.out.println("error while importing own identities: " + e.getMessage());
					}
				}
			}
		} else if(params.get("Message").equals("Identities")) {
			String key;
			String prop_key;
			String prop;
			SimpleFieldSet subset;
			String prop_routingkey;
			String prop_requestkey;
			int prop_puzzlecount;
			long prop_last_fetched;
			long counter = 0;
			long cur_date = System.currentTimeMillis();
			long puzzle_sum = 0;
			long puzzle_sum_total = 0;
			long count_idents_added = 0;
			long count_idents_updated = 0;
			long count_idents_removed = 0;
			Ramstore_ident ident;
			Iterator<String> iter = params.directSubsetNameIterator();
			Iterator<String> subIter;
			synchronized(this.storage.allidents) {
				while(iter.hasNext()) {
					key = iter.next();
					if(key.startsWith("Properties")) {
						subset = params.subset(key);
						subIter = subset.directSubsetNameIterator();
						prop_routingkey = "";
						prop_requestkey = "";
						prop_puzzlecount = 0;
						prop_last_fetched = 0;
						while(subIter.hasNext()) {
							prop_key = subIter.next();
							prop = subset.get(prop_key + ".Name");
							// FIXME check == null: continue
							if(prop.equals("requestURI")) {
								prop_requestkey = subset.get(prop_key + ".Value");
							} else if(prop.equals("lastFetched")) {
								prop_last_fetched = subset.getLong(prop_key + ".Value", 0);
							} else if(prop.equals("IntroductionPuzzleCount")) {
								prop_puzzlecount = subset.getInt(prop_key + ".Value", 0);
							} else if(prop.equals("id")) {
								prop_routingkey = subset.get(prop_key + ".Value"); 
							}
						}
						puzzle_sum_total += prop_puzzlecount;
						if(prop_puzzlecount > 0 && cur_date - prop_last_fetched < Worker.wot_max_age) {
							puzzle_sum += prop_puzzlecount;
							if(! this.storage.allidents.containsKey(prop_routingkey)) {
								this.storage.allidents.put(prop_routingkey, new Ramstore_ident(prop_routingkey, prop_requestkey, prop_puzzlecount, prop_last_fetched));
								count_idents_added ++;
							} else {
								ident = this.storage.allidents.get(prop_routingkey);
								ident.last_fetched = prop_last_fetched;
								ident.puzzlecount = prop_puzzlecount;
								count_idents_updated ++;
							}
						} else {
							if(this.storage.allidents.containsKey(prop_routingkey)) {
								this.storage.allidents.remove(prop_routingkey);
								count_idents_removed ++;
								//System.out.println("removed " + prop_routingkey + " because last_fetched > wot_max_age or puzzlecount < 1");
								// TODO clean puzzles from this identity as well?
							}
						}
						counter ++;
					}
				}
				System.out.println(" - found " + counter + " identities with a total of " + puzzle_sum_total + " puzzles");
				System.out.println(" - added " + count_idents_added + " identities wich have been fetched successfully since " + Worker.wot_max_age_days + " days");
				System.out.println(" - updated " + count_idents_updated + " identities wich have been fetched successfully since " + Worker.wot_max_age_days + " days");
				System.out.println(" - removed " + count_idents_removed + " identities wich have not been fetched since " + Worker.wot_max_age_days + " days or have a puzzlecount < 1");
				System.out.println(" - currently active identities are publishing a total of " + puzzle_sum + " puzzles");
				final long now = System.currentTimeMillis();
				// TODO randomize selection of identities to create puzzle URIs
				for(Ramstore_ident cur_ident : this.storage.allidents.values()) { 
					if(now - cur_ident.last_puzzle_fetched > Worker.wot_required_time_between_puzzles_from_a_single_identity) {
						try {
							this.queue_puzzlefetcher.add(new FreenetURI(cur_ident.requestkey).setKeyType("SSK").setDocName("WebOfTrust|Introduction|" + Worker.sdf.format(new Date(System.currentTimeMillis())) +"|" + this.rng.nextInt(cur_ident.puzzlecount + 1)));
						} catch (MalformedURLException e) {
							System.out.println("error while creating puzzle fetch url from " + cur_ident.requestkey + ": " + e.getMessage());
						}
					}
					if(this.queue_puzzlefetcher.size() == Worker.max_puzzle_uris) {
						break;
					}
				}
				System.out.println(" - available puzzle URIs to fetch from: " + this.queue_puzzlefetcher.size() + "/" + Worker.max_puzzle_uris);
				System.out.println(" - currently cached puzzles: " + this.storage.puzzles.size() + "/" + Worker.wot_puzzle_max_download);
				System.out.println(" - currently fetching " + this.concurrentFetchers + "/" + Ramstore.max_concurrentFetchCount + " puzzles");
				System.out.println(" - currently inserting " + this.concurrentInserters + "/" + Ramstore.max_concurrentInsertCount + " solutions");
			}
			this.waiting_for_identity_refresh = false;
		}
	}
	// fetch callbacks
		@Override
		public synchronized void onSuccess(FetchResult result, ClientGetter state, ObjectContainer container) {
			// FIXME replace with state.getURI().getRoutingKey() and parse it into ASCII
			final String routingkey = state.getURI().toString().split(",", 2)[0].split("@", 2)[1];
			synchronized(this.storage.allidents) {
				if(this.storage.allidents.containsKey(routingkey)) {
					this.storage.allidents.get(routingkey).last_puzzle_fetched = System.currentTimeMillis();
				} else {
					System.out.println("fetched puzzle but can't find ident '" + routingkey + "'. identity possibly deleted because of last_fetched > xs");
				}
			}
			synchronized(this.runningFetches) {
				this.runningFetches.remove(state.getURI());
			}
			Bucket bucket = result.asBucket();
			if(bucket.size() > Worker.wot_puzzle_max_size) {
				System.out.println("this should never happen; puzzle result larger than max_size of " + Worker.wot_puzzle_max_size + " bytes.");
				System.out.println("fetched from " + state.getURI().toString());
				Closer.close(bucket);
				this.concurrentFetchers --;
				this.notify();
				return;
			}
			try {
				InputStream is;
				is = bucket.getInputStream();
				String puzzleID;
				String puzzleType;
				String puzzleMimeType;
				String puzzleData;
				long validUntil;
				synchronized(this.xmlDocumentBuilder) {
					Document xmlDoc = this.xmlDocumentBuilder.parse(is);
					Element puzzle = (Element) xmlDoc.getElementsByTagName("IntroductionPuzzle").item(0);
					puzzleID = puzzle.getAttribute("ID");
					puzzleType = puzzle.getAttribute("Type");
					puzzleMimeType = puzzle.getAttribute("MimeType");
					validUntil = Worker.sdf.parse(puzzle.getAttribute("ValidUntil")).getTime();
					/*
					Element dataElement = (Element)puzzle.getElementsByTagName("Data").item(0);
					byte[] puzzleData = Base64.decodeStandard(dataElement.getAttribute("Value"));
					*/
					puzzleData = ((Element)puzzle.getElementsByTagName("Data").item(0)).getAttribute("Value");
					Closer.close(is);
					Closer.close(bucket);
				}
				
				if(
					puzzleType.equals("Captcha") &&
					puzzleMimeType.equals("image/jpeg") &&
					validUntil - System.currentTimeMillis() > Worker.wot_puzzle_required_validity
				) {
					synchronized(this.storage.puzzles) {
						try {
							this.storage.puzzles.put(puzzleID, new Ramstore_puzzle(puzzleID, puzzleData, validUntil));
						} catch (IllegalBase64Exception e) {
							System.out.println("got invalid puzzle. type: " + puzzleType + "; mime: " + puzzleMimeType + "; error: " + e.getMessage());
							System.out.println("base64: " + puzzleData);
						}
					}
				} else {
					System.out.println("got invalid puzzle. type: " + puzzleType + "; mime: " + puzzleMimeType + "; valid until: " + new Date(validUntil).toString());
				}
			} catch (IOException e) {
				System.out.println("[onFetchSuccess]::IOException " + e.getMessage());
			} catch (SAXException e) {
				System.out.println("[onFetchSuccess]::SAXException " + e.getMessage());
			} catch (ParseException e) {
				System.out.println("[onFetchSuccess]::ParseException (sdf) " + e.getMessage());
			}
			this.concurrentFetchers --;
			this.notify();
		}
		@Override
		public synchronized void onFailure(FetchException e, ClientGetter state, ObjectContainer container) {
			//System.out.println("got fetcherror: " + e.getMessage());
			synchronized(this.runningFetches) {
				this.runningFetches.remove(state.getURI());
			}
			this.concurrentFetchers --;
			this.notify();
		}
	// insert callbacks
		@Override
		public synchronized void onSuccess(BaseClientPutter state, ObjectContainer container) {
			System.out.println("finished inserting solution: " + state.getURI().toString());
			this.concurrentInserters --;
			this.notify();
		}
		@Override
		public synchronized void onFailure(InsertException e, BaseClientPutter state, ObjectContainer container) {
			System.out.println("error while inserting solution: " + state.getURI().toString());
			System.out.println(e.getMessage());
			this.concurrentInserters --;
			this.notify();
		}

	@Override
	public boolean realTimeFlag() {
		return true;
	}
	@Override
	public boolean persistent() {
		return false;
	}
	@Override
	public String getString(String key) {
		return key;
	}
	// unimplemented and not needed callbacks
		@Override
		public void setLanguage(LANGUAGE newLanguage) {
		}
		@Override
		public void onMajorProgress(ObjectContainer container) {
		}
		@Override
		public void removeFrom(ObjectContainer container) {
		}
		@Override
		public void onGeneratedURI(FreenetURI uri, BaseClientPutter state,
				ObjectContainer container) {
		}
		@Override
		public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state,
				ObjectContainer container) {
		}
		@Override
		public void onFetchable(BaseClientPutter state, ObjectContainer container) {
		}
}
