package plugins.lcwot.introduction.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Ramstore {
	public final HashMap<String, Ramstore_puzzle> puzzles;	//   puzzles <uid, puzzle_class>
	public final List<Ramstore_ownIdent> ownidents;			// ownidents <routingkey>
	public final HashMap<String, Ramstore_ident> allidents;	// allidents <routingkey, ident_class>
	public final static int max_concurrentFetchCount = 10;	// fetch max x puzzles at the same time
	public final static int max_concurrentInsertCount = 10;	// insert max x puzzle solutions at the same time
	public final static short fetchpriority = 3;
	public final static short insertpriority = 3;
	public final static long wot_version = 12;
	public final static int wot_xml_introduction_version = 1;
	public Ramstore() {
		this.ownidents = new ArrayList<Ramstore_ownIdent>();
		this.allidents = new HashMap<String, Ramstore_ident>();
		this.puzzles = new HashMap<String, Ramstore_puzzle>();
	}
}
