#!/bin/bash
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
elif [ "$1" = "test" ]; then
    rm -rf ../AirShitTest
    mkdir ../AirShitTest
    cp -r asset ../AirShitTest
    cp *.java ../AirShitTest
    javac -d . *.java
    javac -d ../AirShitTest ../AirShitTest/*.java
    java AirShit.Main
else
    javac -d . *.java
    java AirShit.Main
fi


# javac -d ../AirShitTest *.java
# cp *.java ../AirShitTest