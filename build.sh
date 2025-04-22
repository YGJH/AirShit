clear
rm -rf ../AirShitTest
mkdir ../AirShitTest
cp -r asset ../AirShitTest
# find . -maxdepth 1 -type f ! -name "tt.txt" -exec cp {} ../AirShitTest \;
cp *.java ../AirShitTest
javac -d . *.java
javac -d ../AirShitTest ../AirShitTest/*.java
java AirShit.Main

# javac -d ../AirShitTest *.java
# cp *.java ../AirShitTest