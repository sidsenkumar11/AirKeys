from PIL import Image, ImageDraw, ImageOps
import ast
import base64
import json
import os
import socket
import sys
import numpy as np
import char_predict
import logging

HOST, PORT = '', 5000
id_val = len(os.listdir('./images'))

def handle_data(data):
    global id_val
    # Get points
    try:
        json_obj = json.loads(data)
    except:
        print(data)
        return "Server says: Malformed JSON; try again!"
    points_list = json_obj['points']
    points_list = ast.literal_eval(json_obj['points'])

    # Create image
    img = Image.new('RGB', (json_obj['cols'], json_obj['rows']), (0, 0, 0))
    draw = ImageDraw.Draw(img)
    r = 5
    for point in points_list:
        x, y = point[0], point[1]
        draw.ellipse((x-r, y-r, x+r, y+r), fill=(255,255,255))

    # View and save
    # img.show()
    np_pixels = np.array(img)

    # Crop image and save
    id_val += 1
    img = ImageOps.invert(img.crop(img.getbbox()))
    img.save("images/pic{}.png".format(id_val), "PNG")

    # Return character after running image in neural network
    return char_predict.from_model("images/pic{}.png".format(id_val))

def main():
    # Bind socket to local host and port
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind((HOST, PORT))
    except socket.error as msg:
        print('Bind failed. Error Code : ' + str(msg[0]) + ' Message ' + msg[1])
        sys.exit()

    # Start listening on socket and waiting for connections
    s.listen(10)
    print("Everything loaded! Waiting for connections...")
    while 1:
        conn, addr = s.accept()
        logging.warning('Connected with ' + addr[0] + ':' + str(addr[1]))
        data_len = int(conn.recv(6))
        
        data = ""
        while len(data) < data_len:
            incoming = conn.recv(data_len - len(data))
            if not incoming:
                print("Only received: {}".format(data))
                data = ""
                break
            data += incoming
        letter = handle_data(data)
        conn.sendall(letter)
        conn.close()

    s.close()

if __name__ == '__main__':
    logging.basicConfig(format='[%(asctime)s]: %(message)s', datefmt='%m/%d/%Y %I:%M:%S %p')
    main()
