clear
cp *.java ../AirShitTest
javac -d . *.java
javac -d ../AirShitTest ../AirShitTest/*.java
java AirShit.Main

# javac -d ../AirShitTest *.java
# cp *.java ../AirShitTest