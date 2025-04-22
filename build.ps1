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
        javac -d . *.java
        java AirShit.Main
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