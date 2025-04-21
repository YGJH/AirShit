Client.java是定義client的物件，就是每個user都是一個client。裡投放ip tcp_port udp_port username
FileChooserGUI.jav 就是檔案選擇的gui介面 目前還很醜
SendFileGUI.java就是目前的主要GUI介面
Main.java裏頭主要從main開始看
- broadcast hello 就是把全部的port都問一遍，並發送自己的client資訊
- receive 就是等待有沒有人傳送檔案給自己
- sendfile就是當按下sendfile按鈕時，會送出檔案
- UDPserver 就是不斷的listen新的user的hello message
- 其他的小function自己看
4-12 現在有一堆bug