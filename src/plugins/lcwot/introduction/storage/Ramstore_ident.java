package plugins.lcwot.introduction.storage;

public class Ramstore_ident {
	public boolean ownNick;
	public String routingkey;
	public String requestkey;
	public int puzzlecount;
	public long last_fetched;
	public long last_puzzle_fetched;
	public Ramstore_ident(String routingkey, String requestkey, int puzzlecount, long last_fetched) {
		this.ownNick = false;
		this.routingkey = routingkey;
		this.requestkey = requestkey;
		this.puzzlecount = puzzlecount;
		this.last_fetched = last_fetched;
		this.last_puzzle_fetched = 0;
	}
	public Ramstore_ident(boolean ownNick, String routingkey, String requestkey, int puzzlecount, long last_fetched) {
		this.ownNick = ownNick;
		this.routingkey = routingkey;
		this.requestkey = requestkey;
		this.puzzlecount = puzzlecount;
		this.last_fetched = last_fetched;
		this.last_puzzle_fetched = 0;
	}
	@Override
	public String toString() {
		return this.routingkey + ": puzzlecount " + this.puzzlecount + "; last_fetched " + this.last_fetched + "; requestkey " + this.requestkey;
	}
}
