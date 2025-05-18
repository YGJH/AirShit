param(
    [string]$Action = ""
)

switch ($Action.ToLower()) {
    'push' {
        git add .
        git commit -m 'update'
        git push
        break
    }
    'pull' {
        git reset --hard
        git pull
        break
    }
    'test' {
        $testDir = '..\AirShitTest'
        if (Test-Path $testDir) {
            Remove-Item $testDir -Recurse -Force
        }
        Copy-Item 'asset' -Destination $testDir -Recurse
        Copy-Item '*.java' -Destination $testDir

        # compile original and test copy
        javac -d . *.java
        javac -d . "$testDir\*.java"

        # run
        java AirShit.Main
        break
    }
    default {
        # 修改 javac 命令以實際執行並包含 classpath
        javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d . *.java ui\*.java
        # 修改 java 命令以包含 classpath
        java -cp ".;libs/flatlaf-3.4.1.jar" AirShit.Main
    }

}

# # run default build+run
# .\build.ps1

# # push changes
# .\build.ps1 push

# # pull latest
# .\build.ps1 pull

# # test in temp workspace
# .\build.ps1 test