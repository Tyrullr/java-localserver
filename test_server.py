import urllib.request
import urllib.parse
import urllib.error
import http.cookiejar

def run_tests():
    url_base = "http://127.0.0.1:8080"
    
    # 1. GET Root
    print("Testing GET / ...")
    try:
        response = urllib.request.urlopen(url_base + "/")
        html = response.read().decode('utf-8')
        assert response.status == 200
        assert "Java custom Web Server" in html
        print("[PASS] GET /")
    except Exception as e:
        print("[FAIL] GET /:", e)

    # 2. GET 404
    print("Testing 404 page ...")
    try:
        urllib.request.urlopen(url_base + "/non-existent-page")
        print("[FAIL] GET /non-existent-page did not return error status")
    except urllib.error.HTTPError as e:
        html = e.read().decode('utf-8')
        assert e.code == 404
        assert "404" in html
        assert "Not Found" in html
        print("[PASS] 404 page")
    except Exception as e:
        print("[FAIL] 404 page:", e)

    # 3. Cookies & Session
    print("Testing Session & Cookies ...")
    try:
        cj = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
        
        data = urllib.parse.urlencode({"username": "Marc"}).encode("utf-8")
        res_post = opener.open(url_base + "/set-session", data)
        assert res_post.status == 200 or res_post.geturl() == url_base + "/"
        
        cookies = [c.name for c in cj]
        assert "JSESSIONID" in cookies
        
        res_get = opener.open(url_base + "/check-session")
        html_get = res_get.read().decode('utf-8')
        assert "Hello, Marc" in html_get
        print("[PASS] Session & Cookies")
    except Exception as e:
        print("[FAIL] Session & Cookies:", e)

    # 4. File Upload (POST Multipart)
    print("Testing Multipart Upload ...")
    try:
        boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        body = (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="file"; filename="test_upload.txt"\r\n'
            "Content-Type: text/plain\r\n\r\n"
            "This is a test file upload.\r\n"
            f"--{boundary}--\r\n"
        ).encode("utf-8")
        
        req = urllib.request.Request(
            url_base + "/uploads",
            data=body,
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}"
            }
        )
        
        res = urllib.request.urlopen(req)
        html = res.read().decode('utf-8')
        assert res.status == 200
        assert "Uploaded Successfully" in html
        print("[PASS] Multipart Upload")
    except Exception as e:
        print("[FAIL] Multipart Upload:", e)

    # 5. File Delete
    print("Testing DELETE file ...")
    try:
        req = urllib.request.Request(
            url_base + "/uploads/test_upload.txt",
            method="DELETE"
        )
        res = urllib.request.urlopen(req)
        assert res.status == 200
        print("[PASS] File DELETE")
    except Exception as e:
        print("[FAIL] File DELETE:", e)

    # 6. CGI Execution
    print("Testing CGI execution ...")
    try:
        response = urllib.request.urlopen(url_base + "/cgi-bin/hello.py?name=Antigravity")
        html = response.read().decode('utf-8')
        assert response.status == 200
        assert "Hello, Antigravity!" in html
        assert "REQUEST_METHOD: GET" in html
        print("[PASS] CGI Execution")
    except Exception as e:
        print("[FAIL] CGI Execution:", e)

if __name__ == "__main__":
    run_tests()
