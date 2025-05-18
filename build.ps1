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
    'packing' {
        $AppName = "AirShit"
        $AppVersion = "1.0.0" # 你可以根據需要修改版本號
        $VendorName = "AirShit Project" # 你的名稱或組織名稱
        $AppJarName = "$AppName.jar"
        $MainClass = "AirShit.Main" # 你的主類別
        $FlatLafJarRelativePath = "libs/flatlaf-3.4.1.jar" # FlatLaf JAR 的相對路徑

        # 清理並建立臨時打包目錄
        $PackageBaseDir = ".\package_temp" # 用於存放編譯和打包過程中的臨時檔案
        if (Test-Path $PackageBaseDir) {
            Remove-Item -Recurse -Force $PackageBaseDir
        }
        New-Item -ItemType Directory -Force -Path $PackageBaseDir

        # 1. 編譯原始碼到 build 目錄
        $BuildDir = "$PackageBaseDir\build"
        New-Item -ItemType Directory -Force -Path $BuildDir
        Write-Host "Compiling application sources into $BuildDir..."
        # javac 的 -cp 參數需要包含 FlatLaf JAR
        # -d 指定編譯後 .class 檔案的輸出目錄
        # *.java 編譯目前目錄下的所有 Java 檔案 (例如 Main.java)
        # ui\*.java 編譯 ui 子目錄下的所有 Java 檔案 (例如 ClientPanel.java)
        javac -cp ".;$FlatLafJarRelativePath" -encoding UTF-8 -d $BuildDir *.java ui\*.java
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Compilation failed!"
            exit 1
        }

        # 2. 建立應用程式的 JAR 檔案
        $JPackageInput = "$PackageBaseDir\input" # jpackage 的輸入目錄
        New-Item -ItemType Directory -Force -Path $JPackageInput
        New-Item -ItemType Directory -Force -Path "$JPackageInput\libs" # 在輸入目錄中建立 libs 子目錄

        Write-Host "Creating application JAR: $AppJarName..."
        # 建立 MANIFEST.MF 檔案
        # Main-Class 指定 JAR 的入口點
        # Class-Path 讓 JAR 在執行時可以找到依賴的 FlatLaf JAR (相對於應用程式 JAR 的路徑)
        $ManifestFile = "$BuildDir\MANIFEST.MF"
        $FlatLafJarNameOnly = $FlatLafJarRelativePath.Split('/')[-1] # 取得 flatlaf-3.4.1.jar
        $ManifestContent = "Manifest-Version: 1.0`nMain-Class: $MainClass`nClass-Path: libs/$FlatLafJarNameOnly"
        Set-Content -Path $ManifestFile -Value $ManifestContent

        # 使用 jar 命令建立 JAR 檔案
        # "$JPackageInput\$AppJarName" 是輸出的 JAR 檔案路徑
        # $ManifestFile 是剛才建立的 MANIFEST 檔案
        # -C $BuildDir . 表示從 $BuildDir 目錄下打包所有檔案 (javac 已經建立了正確的套件結構)
        jar cfm "$JPackageInput\$AppJarName" $ManifestFile -C $BuildDir .
        if ($LASTEXITCODE -ne 0) {
            Write-Error "JAR creation failed!"
            exit 1
        }

        # 3. 複製依賴的 JAR (FlatLaf) 到 jpackage 的輸入目錄
        Write-Host "Copying dependent JARs to $JPackageInput\libs..."
        Copy-Item -Path $FlatLafJarRelativePath -Destination "$JPackageInput\libs\"

        # 4. 執行 jpackage 來建立安裝程式
        Write-Host "Running jpackage to create installer..."
        $InstallerOutputDir = ".\dist" # 最終安裝程式的輸出目錄
        if (Test-Path $InstallerOutputDir) {
            Remove-Item -Recurse -Force $InstallerOutputDir
        }
        New-Item -ItemType Directory -Force -Path $InstallerOutputDir

        jpackage --type exe `
            --dest $InstallerOutputDir `
            --input $JPackageInput `
            --name $AppName `
            --main-jar $AppJarName `
            --java-options "-Dfile.encoding=UTF-8" `
            --app-version $AppVersion `
            --vendor $VendorName `
            --win-menu `
            --win-shortcut `
            --icon "asset\kitty.ico" # 如果你有圖示檔案，取消註解並設定路徑

        if ($LASTEXITCODE -ne 0) {
            Write-Error "jpackage failed!"
            exit 1
        }

        Write-Host "Packaging complete. Installer created in '$InstallerOutputDir'."
        Write-Host "Temporary packaging files are in '$PackageBaseDir'. You can delete this folder if needed."
        break
    }
    'wraping' {
        javac -cp ".;libs/flatlaf-3.4.1.jar" -encoding UTF-8 -d out/production/MyApp *.java ui\*.java
        jar cfm MyApp.jar out/production/MyApp/AirShit/META-INF/MANIFEST.MF -C out/production/MyApp .
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