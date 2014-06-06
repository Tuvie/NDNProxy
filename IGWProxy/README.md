IGWProxy is a HTTP proxy over NDN extended from LittleProxy. 

To run the proxy, you can run 'run.bash' in shell.
You can change the port or the username information in the config file 'igw.properties'.

And please run an EGWProxy along with IGWProxy whose name prefix is 'NDNProxy_Local' in order to receive Interests when the IGWProxy cannot connect to Map Server.

Please use jdk1.6 and put bcprov-jdk1.6-1.40.jar in the root directory into the $JAVA_HOME/lib/ext.

If you have questions, please visit our Google Group here:
https://groups.google.com/d/forum/httpoverndn

