clear
if [ "$1" = "push" ]; then
    clear
    git add .
    git commit -m "fuck"
    git push
elif [ "$1" = "pull" ]; then
    clear
    git reset --hard
    git pull
else
    rm -rf ../AirShitTest
    mkdir ../AirShitTest
    cp -r asset ../AirShitTest
    # find . -maxdepth 1 -type f ! -name "tt.txt" -exec cp {} ../AirShitTest \;
    cp *.java ../AirShitTest
    javac -d . *.java
    javac -d ../AirShitTest ../AirShitTest/*.java
    java AirShit.Main
fi


# javac -d ../AirShitTest *.java
# cp *.java ../AirShitTest