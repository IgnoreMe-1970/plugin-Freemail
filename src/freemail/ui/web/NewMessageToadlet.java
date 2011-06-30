/*
 * NewMessageToadlet.java
 * This file is part of Freemail
 * Copyright (C) 2011 Martin Nyhus
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail.ui.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import freemail.Freemail;
import freemail.FreemailAccount;
import freemail.transport.Channel;
import freemail.utils.Logger;
import freemail.wot.Identity;
import freemail.wot.WoTConnection;
import freenet.client.HighLevelSimpleClient;
import freenet.clients.http.PageMaker;
import freenet.clients.http.PageNode;
import freenet.clients.http.SessionManager;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.support.HTMLNode;
import freenet.support.api.Bucket;
import freenet.support.api.HTTPRequest;

public class NewMessageToadlet extends WebPage {
	private final WoTConnection wotConnection;
	private final Freemail freemail;

	NewMessageToadlet(HighLevelSimpleClient client, SessionManager sessionManager, PageMaker pageMaker, WoTConnection wotConnection, Freemail freemail) {
		super(client, pageMaker, sessionManager);
		this.wotConnection = wotConnection;
		this.freemail = freemail;
	}

	@Override
	public void makeWebPage(URI uri, HTTPRequest req, ToadletContext ctx, HTTPMethod method, PageNode page) throws ToadletContextClosedException, IOException {
		switch(method) {
		case GET:
			makeWebPageGet(ctx, page);
			break;
		case POST:
			makeWebPagePost(req, ctx, page);
			break;
		default:
			//This will only happen if a new value is added to HTTPMethod, so log it and send an
			//error message
			assert false : "HTTPMethod has unknown value: " + method;
			Logger.error(this, "HTTPMethod has unknown value: " + method);
			writeHTMLReply(ctx, 200, "OK", "Unknown HTTP method " + method + ". This is a bug in Freemail");
		}
	}

	private void makeWebPageGet(ToadletContext ctx, PageNode page) throws ToadletContextClosedException, IOException {
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		HTMLNode messageBox = addInfobox(contentNode, "New message");
		HTMLNode messageForm = ctx.addFormChild(messageBox, path(), "newMessage");

		HTMLNode recipientBox = addInfobox(messageForm, "To");
		recipientBox.addChild("input", new String[] {"name", "type", "size"},
		                               new String[] {"to",   "text", "100"});

		HTMLNode subjectBox = addInfobox(messageForm, "Subject");
		subjectBox.addChild("input", new String[] {"name",    "type", "size"},
		                             new String[] {"subject", "text", "100"});

		HTMLNode messageBodyBox = addInfobox(messageForm, "Text");
		messageBodyBox.addChild("textarea", new String[] {"name",         "cols", "rows", "class"},
		                                    new String[] {"message-text", "100",  "30",   "message-text"});

		messageForm.addChild("input", new String[] {"type",   "value"},
		                              new String[] {"submit", "Send"});

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private void makeWebPagePost(HTTPRequest req, ToadletContext ctx, PageNode page) throws IOException, ToadletContextClosedException {
		//Read list of recipients. Whitespace seems to be the only reasonable way to separate
		//identities, but people will probably use all sorts of characters that can also appear in
		//nicknames, so the matching should be sufficiently fuzzy to handle that
		Set<String> identities = new HashSet<String>();

		Bucket b = req.getPart("to");
		BufferedReader data;
		try {
			data = new BufferedReader(new InputStreamReader(b.getInputStream(), "UTF-8"));
		} catch(UnsupportedEncodingException e) {
			throw new AssertionError();
		} catch(IOException e) {
			throw new AssertionError();
		}

		String line = data.readLine();
		while(line != null) {
			String[] parts = line.split("\\s");
			for(String part : parts) {
				identities.add(part);
			}
			line = data.readLine();
		}

		Set<Identity> matches = matchIdentities(identities, sessionManager.useSession(ctx).getUserID());

		if(!identities.isEmpty()) {
			//TODO: Handle this properly
			HTMLNode pageNode = page.outer;
			HTMLNode contentNode = page.content;

			HTMLNode errorBox = addErrorbox(contentNode, "Ambiguous identities");
			HTMLNode errorPara = errorBox.addChild("p", "There were " + identities.size() + " " +
					"recipients that could not be found in the Web of Trust:");
			HTMLNode identityList = errorPara.addChild("ul");
			for(String s : identities) {
				identityList.addChild("li", s);
			}

			writeHTMLReply(ctx, 200, "OK", pageNode.generate());
			return;
		}

		//Build message header
		FreemailAccount account = freemail.getAccountManager().getAccount(sessionManager.useSession(ctx).getUserID());
		//TODO: Check for newlines etc.
		//TODO: Add date
		String messageHeader =
			"Subject: " + getBucketAsString(req.getPart("subject")) + "\r\n" +
			"From: " + account.getNickname() + " <" + account.getNickname() + "@" + account.getUsername() + ".freemail>\r\n" +
			"To: " + getBucketAsString(b) + "\r\n" +
			"\r\n";
		InputStream messageHeaderStream = new ByteArrayInputStream(messageHeader.getBytes("UTF-8"));

		for(Identity identity : matches) {
			Channel channel = account.getChannel(identity.getIdentityID());

			Bucket messageText = req.getPart("message-text");
			channel.sendMessage(new SequenceInputStream(messageHeaderStream, messageText.getInputStream()));
		}

		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		HTMLNode infobox = addInfobox(contentNode, "Message sent");
		infobox.addChild("p", "Your message was sent");

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private String getBucketAsString(Bucket b) {
		InputStream is;
		try {
			is = b.getInputStream();
		} catch(IOException e1) {
			return null;
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		byte[] buffer = new byte[1024];
		while(true) {
			int read;
			try {
				read = is.read(buffer);
			} catch(IOException e) {
				return null;
			}
			if(read == -1) {
				break;
			}

			baos.write(buffer, 0, read);
		}

		try {
			return new String(baos.toByteArray(), "UTF-8");
		} catch(UnsupportedEncodingException e) {
			return null;
		}
	}

	/**
	 * Checks the identities listed in {@code identities} against the identities known by WoT. The
	 * identities that found in WoT (without ambiguity) are removed from the list. The returned list
	 * contains the full identity id of the matched identity. If no identities were matched an empty
	 * set is returned.
	 * @param identities the identities to match
	 * @param currentUser the logged in user
	 * @return a Set containing the matched identities
	 */
	private Set<Identity> matchIdentities(Set<String> identities, String currentUser) {
		Set<Identity> wotIdentities = wotConnection.getAllTrustedIdentities(currentUser);
		wotIdentities.addAll(wotConnection.getAllUntrustedIdentities(currentUser));
		wotIdentities.addAll(wotConnection.getAllOwnIdentities());

		Set<Identity> matches = new HashSet<Identity>();
		for(Identity wotIdentity : wotIdentities) {
			Iterator<String> identityIterator = identities.iterator();
			while(identityIterator.hasNext()) {
				String identity = identityIterator.next();
				String identityID = identity.substring(identity.lastIndexOf("@") + 1, identity.length() - ".freemail".length());
				if(wotIdentity.getIdentityID().equals(identityID)) {
					//Identity id match fully so this is the only possible match
					Logger.debug(this, "Matched identity " + identityID);
					matches.add(wotIdentity);
					identityIterator.remove();
				}
			}
		}

		return matches;
	}

	@Override
	public boolean isEnabled(ToadletContext ctx) {
		return sessionManager.sessionExists(ctx);
	}

	@Override
	public String path() {
		return "/Freemail/NewMessage";
	}

	@Override
	boolean requiresValidSession() {
		return true;
	}
}
