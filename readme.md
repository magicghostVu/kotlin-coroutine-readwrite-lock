Read write mutex with coroutine support<br>
This mutex is implemented with writing priority<br>
When some reading are in operation, some write requests come<br>
and subsequent read requests will be executed after all write requests done<br>
<b>This mutex is NOT reentrant<b><br>
Example:<br>
<code>val mutex = ReadWriteMutex()<br>
mutex.read {<br>
    // read your data here<br>
}<br>
mutex.write{<br>
    //write your data here<br>
}
</code>