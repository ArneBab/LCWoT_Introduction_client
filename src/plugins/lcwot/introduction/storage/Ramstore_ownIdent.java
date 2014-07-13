package plugins.lcwot.introduction.storage;

import freenet.keys.FreenetURI;

public class Ramstore_ownIdent {
	public FreenetURI request_uri;
	public String nick;
	public Ramstore_ownIdent(String nick, FreenetURI request_uri) {
		this.nick = nick;
		this.request_uri = request_uri;
	}
	@Override
	public String toString() {
		return "ownIdent with nick " + this.nick + " and request_key " + this.request_uri.toString();
	}
	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Ramstore_ownIdent) {
			return this.request_uri.equalsKeypair(((Ramstore_ownIdent) obj).request_uri);
		}
		return false; 
	}
}
