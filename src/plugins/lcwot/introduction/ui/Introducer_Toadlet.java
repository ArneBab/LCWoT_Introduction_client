package plugins.lcwot.introduction.ui;

import java.io.IOException;
import java.net.URI;
import java.util.Iterator;

import plugins.lcwot.introduction.Worker;
import plugins.lcwot.introduction.storage.Ramstore_ownIdent;
import plugins.lcwot.introduction.storage.Ramstore_puzzle;

import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker.RenderParameters;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

public class Introducer_Toadlet extends Toadlet {
	private final String path;
	private final RenderParameters rp;
	private final Worker ptrWorker;
	
	public Introducer_Toadlet(Worker ptrWorker, String path, HighLevelSimpleClient client) {
		super(client);
		this.path = path;
		this.rp = new RenderParameters();
		this.ptrWorker = ptrWorker;
	}

	@Override
	public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		if(uri.toString().startsWith(this.path + "/puzzles/")) {
			String requestURI = uri.toString().substring((this.path + "/puzzles/").length());
			if(requestURI.length() <= 5) {
				writeHTMLReply(ctx, 404, "not found", "not found");
				return;
			}
			String puzzle_id = requestURI.substring(0, requestURI.length() - 5);
			Ramstore_puzzle puzzle = this.ptrWorker.getStorage().puzzles.get(puzzle_id);
			if(puzzle != null) {
				writeReply(ctx, 200, "image/jpeg", "OK", puzzle.puzzle, 0, puzzle.puzzle.length);
			} else {
				writeHTMLReply(ctx, 404, "not found", "not found");
			}
			return;
		}
		PageNode page = ctx.getPageMaker().getPageNode("LCWoT Introducer", ctx, rp);
		page = getCaptchaPage(page, ctx);
		writeHTMLReply(ctx, 200, "OK", page.outer.generate());
	}
	
	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		String solved_by = request.getPartAsStringFailsafe("ident_selector", 255);
		PageNode page = ctx.getPageMaker().getPageNode("LCWoT Introducer", ctx, rp);
		if(request.getPartAsStringFailsafe("action", 255).equals("refresh identities from WebOfTrust")) {
			ptrWorker.request_local_identity_update();
			HTMLNode p = new HTMLNode("p");
			p.addChild("span", "issued local identity update, you might need to ");
			HTMLNode a = new HTMLNode("a", "refresh");
			a.addAttribute("href", this.path);
			p.addChild(a);
			p.addChild("span", " this page to see recently created identities.");
			page.content.addChild(p);
			page = getCaptchaPage(page, ctx, solved_by);
			writeHTMLReply(ctx, 200, "OK", page.outer.generate());
			return;
		}
		String[] parts = request.getParts();
		String solution;
		boolean some_success = false;
		Ramstore_puzzle puzzle;
		for(String part : parts) {
			if(part.length() == 80 && part.indexOf('@') == 36) {
				synchronized(ptrWorker.getStorage().puzzles) {
					puzzle = ptrWorker.getStorage().puzzles.get(part);
					if(puzzle != null) {
						solution = request.getPartAsStringFailsafe(part, 10);
						if(!solution.equals("")) {
							puzzle.setSolved(solution, solved_by);
							ptrWorker.getStorage().puzzles.remove(part);
							ptrWorker.addSolution(puzzle);
							some_success = true;
						}
					}
				}
			}/* else {
				page.content.addChild("p", part + ": " + request.getPartAsStringFailsafe(part, 10000));
			}*/
		}
		if(some_success) {
			HTMLNode p = new HTMLNode("p");
			p.addChild("b", "Solutions received, trying to upload.");
			page.content.addChild(p);
		}
		page = getCaptchaPage(page, ctx, solved_by);
		writeHTMLReply(ctx, 200, "OK", page.outer.generate());
	}

	private PageNode getCaptchaPage(PageNode page, ToadletContext ctx) {
		return getCaptchaPage(page, ctx, "");
	}
	
	private PageNode getCaptchaPage(PageNode page, ToadletContext ctx, String selected_identity) {
		HTMLNode form = ctx.addFormChild(page.content, "#", "lcwot_introduction_client");
		HTMLNode cap_div;
		HTMLNode cap_img;
		HTMLNode cap_input;
		int counter = 0;
		Ramstore_puzzle puzzle;
		synchronized (ptrWorker.getStorage().puzzles) {
			Iterator<Ramstore_puzzle> iter = ptrWorker.getStorage().puzzles.values().iterator();
			if(iter.hasNext()){
				// catch default action while sending form via RETURN
				HTMLNode input = new HTMLNode("input");
				input.addAttribute("type", "submit");
				input.addAttribute("value", "Submit solutions");
				input.addAttribute("name", "action");
				input.addAttribute("style", "display: none;");
				form.addChild(input);
				HTMLNode select = new HTMLNode("select");
				select.addAttribute("name", "ident_selector");
				HTMLNode option;
				synchronized(ptrWorker.getStorage().ownidents) {
					for(Ramstore_ownIdent ident : ptrWorker.getStorage().ownidents) {
						option = new HTMLNode("option", ident.nick);
						option.addAttribute("value", ident.request_uri.toString());
						if( ! selected_identity.equals("")  &&
							ident.request_uri.toString().split("/", 2)[0].equals(selected_identity.split("/", 2)[0])
						) {
							option.addAttribute("selected", "selected");
						}
						select.addChild(option);
					}
				}
				form.addChild("label", "Identity to introduce: ");
				form.addChild(select);
				form.addChild("span", " ");
				input = new HTMLNode("input");
				input.addAttribute("type", "submit");
				input.addAttribute("value", "refresh identities from WebOfTrust");
				input.addAttribute("name", "action");
				form.addChild(input);
				form.addChild("br");
				form.addChild("br");
			}
			while(counter < Worker.wot_ui_max_puzzles_per_page && iter.hasNext()) {
				puzzle = iter.next();
				cap_div = new HTMLNode("div");
				cap_div.addAttribute("style", "display: inline-block; padding: 2px;");
				cap_img = new HTMLNode("img");
				cap_img.addAttribute("style", "width: 200px;");
				//cap_img.addAttribute("src", "data:image/jpeg;base64," + puzzle.puzzle_base64);
				cap_img.addAttribute("src", this.path + "/puzzles/" + puzzle.uid_routingkey + ".jpeg");
				cap_div.addChild(cap_img);
				cap_div.addChild("br");
				cap_input = new HTMLNode("input");
				cap_input.addAttribute("style", "width: 196px;");
				cap_input.addAttribute("type", "text");
				cap_input.addAttribute("name", puzzle.uid_routingkey);
				cap_div.addChild(cap_input);
				form.addChild(cap_div);
				counter ++;
			}
		}
		HTMLNode p = new HTMLNode("p");
		if(counter > 0) {
			p.addChild("span", "Solve at least 20 captchas and wait some hours for other nodes to pick up your solutions.");
			p.addChild("br");
			p.addChild("span", "It doesn't matter if you can't solve some of them.");
			form.addChild(p);
			HTMLNode input = new HTMLNode("input");
			input.addAttribute("type", "submit");
			input.addAttribute("value", "Submit solutions");
			input.addAttribute("name", "action");
			form.addChild(input);
		} else {
			p.addChild("span", "We don't have any unsolved puzzles currently.");
			p.addChild("br");
			p.addChild("span", "Try to refresh this page in a few minutes.");
			form.addChild(p);
		}
		page.content.addAttribute("style", "margin: 1em;");
		return page;
	}
	
	@Override
	public String path() {
		return this.path;
	}
}
