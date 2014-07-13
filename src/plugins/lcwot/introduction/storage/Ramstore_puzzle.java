package plugins.lcwot.introduction.storage;

import java.util.Date;

import freenet.support.Base64;
import freenet.support.IllegalBase64Exception;

import plugins.lcwot.introduction.Worker;

public class Ramstore_puzzle {
	public String uid_routingkey;
	//public String puzzle_base64;
	public long valid_until;
	public String solvedBy;
	public String solution;
	public byte puzzle[];
	public Ramstore_puzzle(String uid_routingkey, String puzzle_base64, long valid_until) throws IllegalBase64Exception {
		this.uid_routingkey = uid_routingkey;
		//this.puzzle_base64 = puzzle_base64;
		//this.puzzle = Base64.decode(puzzle_base64);
		this.puzzle = Base64.decodeStandard(puzzle_base64);
		this.valid_until = valid_until;
		this.solvedBy = "";
		this.solution = "";
		//System.out.println("got new puzzle: " + this.toString());
	}
	public void setSolved(String solution, String solvedBy) {
		this.solution = solution;
		this.solvedBy = solvedBy;
	}
	@Override
	public String toString() {
		return this.uid_routingkey + ": valid until " + Worker.sdf.format(new Date(this.valid_until));
	}
}
