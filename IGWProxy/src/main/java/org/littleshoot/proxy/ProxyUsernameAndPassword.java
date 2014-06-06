package org.littleshoot.proxy;


public class ProxyUsernameAndPassword implements ProxyAuthenticator {

	private String username;
	private String password;
	
	public ProxyUsernameAndPassword(String username, String password){
		this.username = username;
		this.password = password;
	}
	
	
	@Override
	public boolean authenticate(String username, String password) {
		// TODO Auto-generated method stub
		if(username.equals(this.username) && password.equals(this.password)){
			return true;
		}
		return false;
	}

}
